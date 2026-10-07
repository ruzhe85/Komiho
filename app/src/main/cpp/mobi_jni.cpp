// Komiho: MOBI/AZW3/AZW 解析 JNI 薄包装。只做「纯抽图」——遍历 rawml->resources
// 按记录顺序把内嵌图片部件落盘，并带回 title/author/封面，供 MobiPageLoader 交付
// 既有解码+增强管线（与 EPUB 的「文档结构定位图片」语义对齐，不做文字渲染）。
// 解析引擎是 vendored libmobi（见 mobi/ 目录），编译为本目标链接的独立 libmobi.so。
#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "mobi/mobi.h"

#define LOG_TAG "KomihoMobi"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

const char kDrmExceptionClass[] = "eu/kanade/tachiyomi/util/mobi/MobiDrmException";
const char kIoExceptionClass[] = "java/io/IOException";

[[noreturn]] void throwJava(JNIEnv *env, const char *cls, const std::string &msg) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass(cls);
    if (c == nullptr) {
        env->ExceptionClear();
        c = env->FindClass("java/lang/RuntimeException");
    }
    if (c != nullptr) env->ThrowNew(c, msg.c_str());
}

bool isImageType(MOBIFiletype type) {
    return type == T_JPG || type == T_PNG || type == T_GIF || type == T_BMP || type == T_SVG;
}

jstring toJString(JNIEnv *env, const char *s) {
    if (s == nullptr) return nullptr;
    return env->NewStringUTF(s);
}

} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_eu_kanade_tachiyomi_util_mobi_MobiExtractor_nativeExtract(
        JNIEnv *env, jclass, jstring jPath, jstring jOutDir) {
    const char *path = env->GetStringUTFChars(jPath, nullptr);
    const char *outDir = env->GetStringUTFChars(jOutDir, nullptr);
    if (path == nullptr || outDir == nullptr) {
        if (path != nullptr) env->ReleaseStringUTFChars(jPath, path);
        if (outDir != nullptr) env->ReleaseStringUTFChars(jOutDir, outDir);
        throwJava(env, kIoExceptionClass, "mobi: 参数获取失败");
        return nullptr;
    }

    MOBIData *m = mobi_init();
    if (m == nullptr) {
        env->ReleaseStringUTFChars(jPath, path);
        env->ReleaseStringUTFChars(jOutDir, outDir);
        throwJava(env, kIoExceptionClass, "mobi: 初始化失败");
        return nullptr;
    }

    MOBI_RET ret = mobi_load_filename(m, path);
    if (ret != MOBI_SUCCESS) {
        mobi_free(m);
        env->ReleaseStringUTFChars(jPath, path);
        env->ReleaseStringUTFChars(jOutDir, outDir);
        if (ret == MOBI_FILE_ENCRYPTED) {
            throwJava(env, kDrmExceptionClass, "mobi: file is encrypted");
        } else {
            throwJava(env, kIoExceptionClass, "mobi: 打开失败 (ret=" + std::to_string(ret) + ")");
        }
        return nullptr;
    }

    // DRM（Kindle 个人图书无 key 无法解密）——明确报错，不做半吊子解析。
    if (mobi_is_encrypted(m)) {
        mobi_free(m);
        env->ReleaseStringUTFChars(jPath, path);
        env->ReleaseStringUTFChars(jOutDir, outDir);
        LOGW("mobi is DRM protected: %s", path);
        throwJava(env, kDrmExceptionClass, "mobi: file is DRM protected");
        return nullptr;
    }

    MOBIRawml *rawml = mobi_init_rawml(m);
    if (rawml == nullptr) {
        mobi_free(m);
        env->ReleaseStringUTFChars(jPath, path);
        env->ReleaseStringUTFChars(jOutDir, outDir);
        throwJava(env, kIoExceptionClass, "mobi: rawml 初始化失败");
        return nullptr;
    }
    ret = mobi_parse_rawml(rawml, m);
    if (ret != MOBI_SUCCESS) {
        mobi_free_rawml(rawml);
        mobi_free(m);
        env->ReleaseStringUTFChars(jPath, path);
        env->ReleaseStringUTFChars(jOutDir, outDir);
        if (ret == MOBI_FILE_ENCRYPTED) {
            throwJava(env, kDrmExceptionClass, "mobi: file is encrypted");
        } else {
            throwJava(env, kIoExceptionClass, "mobi: 解析失败 (ret=" + std::to_string(ret) + ")");
        }
        return nullptr;
    }

    // 按资源链表顺序（即记录顺序）抽取图片部件。uid → 落盘文件路径，
    // 供 EXTH 封面定位复用；解析完把 rawml 释放，避免整本常驻内存。
    std::vector<std::string> images;
    std::vector<std::pair<size_t, std::string>> uidToPath;
    for (MOBIPart *part = rawml->resources; part != nullptr; part = part->next) {
        if (!isImageType(part->type)) continue;
        MOBIFileMeta meta = mobi_get_filemeta_by_type(part->type);
        char name[32];
        snprintf(name, sizeof(name), "%04zu.%s", images.size() + 1, meta.extension);
        std::string outPath = std::string(outDir) + "/" + name;
        FILE *f = fopen(outPath.c_str(), "wb");
        if (f == nullptr) {
            LOGW("mobi: write image failed: %s", outPath.c_str());
            continue;
        }
        size_t written = part->size > 0 ? fwrite(part->data, 1, part->size, f) : 0;
        fclose(f);
        if (written != part->size) {
            LOGW("mobi: short write %s (%zu/%zu)", outPath.c_str(), written, part->size);
            remove(outPath.c_str());
            continue;
        }
        uidToPath.emplace_back(part->uid, outPath);
        images.push_back(outPath);
    }

    // 封面：EXTH_COVEROFFSET 相对首个资源记录定位；失败回落第一张图。
    std::string coverPath;
    if (!images.empty()) {
        coverPath = images.front();
        const MOBIExthHeader *cover = mobi_get_exthrecord_by_tag(m, EXTH_COVEROFFSET);
        if (cover != nullptr && cover->data != nullptr && cover->size >= 1) {
            uint32_t offset = mobi_decode_exthvalue(static_cast<const unsigned char *>(cover->data), cover->size);
            if (offset != MOBI_NOTSET) {
                size_t uid = mobi_get_first_resource_record(m) + offset;
                for (const auto &e : uidToPath) {
                    if (e.first == uid) {
                        coverPath = e.second;
                        break;
                    }
                }
            }
        }
    }

    char *title = mobi_meta_get_title(m);
    char *author = mobi_meta_get_author(m);
    std::string titleStr = title != nullptr ? title : "";
    std::string authorStr = author != nullptr ? author : "";
    free(title);
    free(author);

    mobi_free_rawml(rawml);
    mobi_free(m);
    env->ReleaseStringUTFChars(jPath, path);
    env->ReleaseStringUTFChars(jOutDir, outDir);

    LOGD("mobi extracted: images=%zu cover=%s title=%s", images.size(), coverPath.c_str(), titleStr.c_str());

    // 返回 [title, author, coverPath, img1, img2, ...]；coverPath 可能为空串。
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(images.size() + 3), strCls, nullptr);
    env->SetObjectArrayElement(result, 0, toJString(env, titleStr.c_str()));
    env->SetObjectArrayElement(result, 1, toJString(env, authorStr.c_str()));
    env->SetObjectArrayElement(result, 2, toJString(env, coverPath.c_str()));
    for (size_t i = 0; i < images.size(); i++) {
        env->SetObjectArrayElement(result, static_cast<jsize>(i + 3), toJString(env, images[i].c_str()));
    }
    return result;
}
