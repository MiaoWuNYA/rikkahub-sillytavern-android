// hdguard.c — 插件密钥派生因子的运行时供给
//
// 设计目标：让 PLUGIN_KDF_FACTOR 不再以完整明文出现在任何可 grep/strings 的位置。
//
// 做法：
//   1. 因子按位异或后再分段存储，段长度不等，段间插入无关字节作噪音；
//   2. 运行时按索引表拼接，只有全部段正确组合才还原出因子；
//   3. 拼接前校验调用方证书摘要，使把 .so 单独抠出来调用也不成立。
//
// 诚实的边界：这挡得住 grep 与 strings 的一击命中，
// 挡不住耐心读反汇编的人。它的作用是提高成本，不是提供不可破解性。
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

#define LOG_TAG "HdGuard"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ── 混淆后的因子分段 ────────────────────────────────────────────
// 每段都以 0x5A 逐字节异或（XOR 常量不直接以字符串形式出现）。
// 段间夹入的噪音字节由 SEG_OFF/SEG_LEN 精确跳过。
static const unsigned char BLOB[] = {
    0x39, 0x39, 0x6C, 0x6F, 0x6F, 0x3C, 0x6D, 0x6C, 0x6A, 0x11, 0x22,
    0x33, 0x62, 0x6C, 0x62, 0x63, 0x38, 0x38, 0x6F, 0x3C, 0xAA, 0xBB,
    0x3F, 0x6C, 0x6A, 0x6A, 0x69, 0x3E, 0x68, 0x6A, 0x6C, 0x3C, 0x62,
    0x77, 0x3F, 0x69, 0x62, 0x6D, 0x69, 0x6B, 0x39, 0x42, 0x43, 0x44,
    0x55, 0x68, 0x39, 0x39, 0x6A, 0x39, 0x38, 0x3B, 0x3E, 0x3F, 0x62,
    0x99, 0x68, 0x69, 0x3B, 0x62, 0x69, 0x6B, 0x63, 0x6E, 0x39, 0xE1,
    0xE2, 0x6F, 0x62, 0x38, 0x6E, 0x6C, 0x3F, 0x3C, 0x38, 0x6B, 0x68,
    0x08, 0x09, 0x0A,
};

// 每段在 BLOB 中的偏移与长度
static const int SEG_OFF[] = { 0, 12, 22, 34, 45, 56, 67 };
static const int SEG_LEN[] = { 9, 8, 11, 7, 10, 9, 10 };
static const int SEG_N   = 7;

static const unsigned char XOR_K = 0x5A;

// ── 运行时校验：调用方证书摘要前缀 ──────────────────────────────
// 与 PluginCrypto 使用同一份签名证书摘要，避免 .so 被单独抠出调用。
// 期望的证书摘要前缀（PluginCrypto 所用的 hex 形态前 16 字符），
// 同样经 XOR 混淆，避免直接读出。
// 期望的证书摘要前缀（hex 形态前 16 字符）。
// 与 BLOB 的前 16 字节逐字节异或存储：密钥来自另一份数组，
// 使编译器无法把本数组折叠成可识别的明文常量。
// 期望的证书摘要前缀（hex 形态前 16 字符）。
// 与 BLOB 中按 (i*7+3) 跳跃取出的字节异或存储——
// 下标是运行时计算的表达式，编译器无法在编译期把两个常量数组折叠成明文。
static const unsigned char EXPECT_PREFIX[] = {
    0x09, 0x10, 0x0D, 0x09, 0x0F, 0x0A, 0x58, 0x07, 0x5A, 0xD5, 0x0C, 0x0A, 0x5F, 0x5A, 0x82, 0x5E,
};

static char g_factor[80];
static int  g_ready = 0;

// 还原段：先拼接再整体异或，避免单段异或后出现可读子串
static void build_factor(void) {
    if (g_ready) return;
    int pos = 0;
    unsigned char raw[80];
    for (int s = 0; s < SEG_N && pos < 72; ++s) {
        for (int i = 0; i < SEG_LEN[s] && pos < 72; ++i) {
            raw[pos++] = BLOB[SEG_OFF[s] + i];
        }
    }
    // 全段拼接完成后统一还原
    for (int i = 0; i < pos; ++i) {
        unsigned char c = raw[i] ^ XOR_K;
        g_factor[i] = (char) c;
    }
    g_factor[pos] = 0;
    g_ready = 1;
}

// 校验调用方提供的证书摘要前缀
static int check_prefix(const unsigned char *p, int n) {
    if (n < (int) sizeof(EXPECT_PREFIX)) return 0;
    // EXPECT_PREFIX 存的就是「期望值逐字节 XOR_K」后的形态，
    // 因此把入参也做一次同样的 XOR 即可直接比对，
    // 运行时不需要（也不能）先还原——否则还原结果会成为编译器可见的常量，
    // 被折叠进 .rodata，等于把明文重新写回二进制。
    // 运行期逐次计算跳跃下标，避免出现「常量数组 ^ 常量数组」这种
    // 编译器可直接折叠成明文字面量的形态。
    const int m = (int) sizeof(BLOB);
    unsigned char acc = 0;
    for (int i = 0; i < (int) sizeof(EXPECT_PREFIX); ++i) {
        int idx = (i * 7 + 3) % m;
        unsigned char k = (unsigned char) (BLOB[idx] ^ (unsigned char)(i * 31 + 17));
        acc |= (unsigned char) (p[i] ^ EXPECT_PREFIX[i] ^ k);
    }
    return acc == 0;
}

JNIEXPORT jstring JNICALL
Java_com_soreverse_mcp_nativecore_RizinNativeEngine_hdKdfFactor(
        JNIEnv *env, jobject thiz, jbyteArray cert_digest) {
    (void) thiz;
    if (!cert_digest) return NULL;
    jsize n = (*env)->GetArrayLength(env, cert_digest);
    if (n <= 0) return NULL;

    jbyte buf[64];
    jsize take = n < 64 ? n : 64;
    (*env)->GetByteArrayRegion(env, cert_digest, 0, take, buf);

    if (!check_prefix((const unsigned char *) buf, (int) take)) {
        LOGI("cert digest mismatch, factor withheld");
        return NULL;
    }
    build_factor();
    return (*env)->NewStringUTF(env, g_factor);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm; (void) reserved;
    LOGI("hdguard loaded");
    return JNI_VERSION_1_6;
}
