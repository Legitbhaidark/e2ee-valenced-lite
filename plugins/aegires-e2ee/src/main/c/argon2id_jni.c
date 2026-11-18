/*
 * Argon2id v1.3 (RFC 9106), one lane, no secret / associated data, with its own BLAKE2b.
 * Same algorithm as Argon2id.java; the JNI entry point is aegires.e2ee.NativeArgon2.hashNative.
 * Built by the Gradle task buildNativeLibs (Android NDK) into lib/<abi>/libaegirescrypto.so inside
 * plugin.jar. NativeArgon2.java checks it against the Java version before it is used.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* ARMv8 SHA-2 instructions (AArch64 only). The code is compiled with a per-function target attribute, so no
 * global -march flag is needed and the same .so still loads on CPUs without the extension: the hardware
 * path is picked at run time (AT_HWCAP) and verified against the portable code before it is trusted. */
#if defined(__aarch64__) && (defined(__linux__) || defined(__ANDROID__)) && defined(__ARM_NEON) \
    && (defined(__clang__) || defined(__GNUC__))
#  include <arm_neon.h>
#  include <sys/auxv.h>
#  include <asm/hwcap.h>
#  ifndef HWCAP_SHA2
#    define HWCAP_SHA2 (1 << 6)
#  endif
#  if defined(__clang__)
#    define SHA2_TARGET __attribute__((target("sha2")))
#  else
#    define SHA2_TARGET __attribute__((target("+crypto")))
#  endif
#  define AEGIRES_SHA256_HW 1
#else
#  define AEGIRES_SHA256_HW 0
#endif


#define WORDS 128
#define SYNC_POINTS 4
#define TYPE_ID 2

static const uint64_t IV[8] = {
    0x6a09e667f3bcc908ULL, 0xbb67ae8584caa73bULL, 0x3c6ef372fe94f82bULL, 0xa54ff53a5f1d36f1ULL,
    0x510e527fade682d1ULL, 0x9b05688c2b3e6c1fULL, 0x1f83d9abfb41bd6bULL, 0x5be0cd19137e2179ULL,
};
static const uint8_t SIGMA[10][16] = {
    {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15},
    {14,10,4,8,9,15,13,6,1,12,0,2,11,7,5,3},
    {11,8,12,0,5,2,15,13,10,14,3,6,7,1,9,4},
    {7,9,3,1,13,12,11,14,2,6,5,10,4,0,15,8},
    {9,0,5,7,2,4,10,15,14,1,11,12,6,8,3,13},
    {2,12,6,10,0,11,8,3,4,13,7,5,15,14,1,9},
    {12,5,1,15,14,13,4,10,0,7,6,3,9,2,8,11},
    {13,11,7,14,12,1,3,9,5,0,15,4,8,6,2,10},
    {6,15,14,9,11,3,0,8,12,2,13,7,1,4,10,5},
    {10,2,8,4,7,6,1,5,15,11,9,14,3,12,13,0},
};

static inline uint64_t rotr64(uint64_t x, int r){ return (x>>r) | (x<<(64-r)); }

static void g_b2(uint64_t v[16], const uint64_t m[16], int a,int b,int c,int d,int x,int y){
    v[a] = v[a] + v[b] + m[x];
    v[d] = rotr64(v[d]^v[a], 32);
    v[c] = v[c] + v[d];
    v[b] = rotr64(v[b]^v[c], 24);
    v[a] = v[a] + v[b] + m[y];
    v[d] = rotr64(v[d]^v[a], 16);
    v[c] = v[c] + v[d];
    v[b] = rotr64(v[b]^v[c], 63);
}
static void compress_b2(uint64_t h[8], const uint8_t block[128], uint64_t m[16], uint64_t v[16], uint64_t t, int last){
    for(int i=0;i<16;i++){
        uint64_t w=0;
        for(int b=7;b>=0;b--) w=(w<<8)|(uint64_t)block[i*8+b];
        m[i]=w;
    }
    memcpy(v,h,8*sizeof(uint64_t));
    memcpy(v+8,IV,8*sizeof(uint64_t));
    v[12]^=t;
    if(last) v[14]=~v[14];
    for(int r=0;r<12;r++){
        const uint8_t *s=SIGMA[r%10];
        g_b2(v,m,0,4,8,12,s[0],s[1]);
        g_b2(v,m,1,5,9,13,s[2],s[3]);
        g_b2(v,m,2,6,10,14,s[4],s[5]);
        g_b2(v,m,3,7,11,15,s[6],s[7]);
        g_b2(v,m,0,5,10,15,s[8],s[9]);
        g_b2(v,m,1,6,11,12,s[10],s[11]);
        g_b2(v,m,2,7,8,13,s[12],s[13]);
        g_b2(v,m,3,4,9,14,s[14],s[15]);
    }
    for(int i=0;i<8;i++) h[i]^=v[i]^v[i+8];
}
static void blake2b_hash(const uint8_t *in, size_t inlen, int outlen, uint8_t *out){
    uint64_t h[8]; memcpy(h,IV,sizeof(IV)); h[0]^=0x01010000ULL ^ (uint32_t)outlen;
    uint64_t m[16], v[16]; uint8_t block[128]; uint64_t t=0; size_t pos=0;
    while(inlen-pos>128){
        memcpy(block,in+pos,128); t+=128; compress_b2(h,block,m,v,t,0); pos+=128;
    }
    size_t rem=inlen-pos; memset(block,0,128); memcpy(block,in+pos,rem); t+=rem; compress_b2(h,block,m,v,t,1);
    for(int i=0;i<outlen;i++) out[i]=(uint8_t)(h[i>>3]>>(8*(i&7)));
}
static void hprime(const uint8_t *in, size_t inlen, int outlen, uint8_t *out){
    uint8_t *withlen=(uint8_t*)malloc(4+inlen);
    if(!withlen) return;
    withlen[0]=(uint8_t)outlen; withlen[1]=(uint8_t)(outlen>>8); withlen[2]=(uint8_t)(outlen>>16); withlen[3]=(uint8_t)(outlen>>24);
    memcpy(withlen+4,in,inlen);
    if(outlen<=64){ blake2b_hash(withlen,4+inlen,outlen,out); memset(withlen,0,4+inlen); free(withlen); return; }
    uint8_t v[64]; blake2b_hash(withlen,4+inlen,64,v); memcpy(out,v,32); int pos=32, remaining=outlen-32;
    while(remaining>64){ blake2b_hash(v,64,64,v); memcpy(out+pos,v,32); pos+=32; remaining-=32; }
    blake2b_hash(v,64,remaining,v); memcpy(out+pos,v,remaining);
    memset(v,0,sizeof(v)); memset(withlen,0,4+inlen); free(withlen);
}

/* BlaMka multiply-add. The 32x32->64 multiply is one instruction (umull) on ARM. */
static inline __attribute__((always_inline)) uint64_t mix64(uint64_t x,uint64_t y){ uint64_t lo=(x&0xFFFFFFFFULL)*(y&0xFFFFFFFFULL); return x+y+2*lo; }

/* One G step on four local variables (they stay in registers instead of going through memory). */
#define GB(a,b,c,d) do { \
    a=mix64(a,b); d=rotr64(d^a,32); \
    c=mix64(c,d); b=rotr64(b^c,24); \
    a=mix64(a,b); d=rotr64(d^a,16); \
    c=mix64(c,d); b=rotr64(b^c,63); \
} while(0)

/* Permutation P over 16 words picked from v: load into locals, 8 G steps, store back. */
static inline __attribute__((always_inline)) void p_a(uint64_t *v, int i0,int i1,int i2,int i3,int i4,int i5,int i6,int i7,int i8,int i9,int i10,int i11,int i12,int i13,int i14,int i15){
    uint64_t a0=v[i0],a1=v[i1],a2=v[i2],a3=v[i3],a4=v[i4],a5=v[i5],a6=v[i6],a7=v[i7];
    uint64_t a8=v[i8],a9=v[i9],a10=v[i10],a11=v[i11],a12=v[i12],a13=v[i13],a14=v[i14],a15=v[i15];
    GB(a0,a4,a8,a12); GB(a1,a5,a9,a13); GB(a2,a6,a10,a14); GB(a3,a7,a11,a15);
    GB(a0,a5,a10,a15); GB(a1,a6,a11,a12); GB(a2,a7,a8,a13); GB(a3,a4,a9,a14);
    v[i0]=a0; v[i1]=a1; v[i2]=a2; v[i3]=a3; v[i4]=a4; v[i5]=a5; v[i6]=a6; v[i7]=a7;
    v[i8]=a8; v[i9]=a9; v[i10]=a10; v[i11]=a11; v[i12]=a12; v[i13]=a13; v[i14]=a14; v[i15]=a15;
}
static void fill_block(const uint64_t *x,const uint64_t *y,uint64_t *out,int xorout,uint64_t r[WORDS],uint64_t z[WORDS]){
    for(int i=0;i<WORDS;i++){ uint64_t t=x[i]^y[i]; r[i]=t; z[i]=t; }
    for(int i=0;i<8;i++){ int b=16*i; p_a(z,b,b+1,b+2,b+3,b+4,b+5,b+6,b+7,b+8,b+9,b+10,b+11,b+12,b+13,b+14,b+15); }
    for(int i=0;i<8;i++){ int b=2*i; p_a(z,b,b+1,b+16,b+17,b+32,b+33,b+48,b+49,b+64,b+65,b+80,b+81,b+96,b+97,b+112,b+113); }
    if(xorout){ for(int i=0;i<WORDS;i++) out[i]^=z[i]^r[i]; } else { for(int i=0;i<WORDS;i++) out[i]=z[i]^r[i]; }
}
static void bytes_to_block(const uint8_t *in,uint64_t *out){
    for(int i=0;i<WORDS;i++){ uint64_t w=0; for(int b=7;b>=0;b--) w=(w<<8)|(uint64_t)in[i*8+b]; out[i]=w; }
}
static void block_to_bytes(const uint64_t *in,uint8_t *out){
    for(int i=0;i<WORDS;i++) for(int b=0;b<8;b++) out[i*8+b]=(uint8_t)(in[i]>>(8*b));
}
static void put_le32(uint8_t *b,int off,uint32_t v){ b[off]=(uint8_t)v; b[off+1]=(uint8_t)(v>>8); b[off+2]=(uint8_t)(v>>16); b[off+3]=(uint8_t)(v>>24); }

static int argon2id_hash(const uint8_t *password,size_t pwdlen,const uint8_t *salt,size_t saltlen,int passes,int memory_kib,int outlen,uint8_t *tag){
    if(passes<1||memory_kib<8||saltlen<8||outlen<4) return -1;
    int blocks = 4*(memory_kib/4);
    int lane_length = blocks;
    int segment_length = lane_length / SYNC_POINTS;
    if(segment_length <= 0) return -1;
    uint64_t (*mem)[WORDS] = (uint64_t(*)[WORDS])calloc((size_t)blocks, sizeof(*mem));
    if(!mem) return -1;

    size_t buflen = 10*4 + pwdlen + saltlen;
    uint8_t *buf=(uint8_t*)malloc(buflen); if(!buf){ free(mem); return -1; }
    int o=0;
    put_le32(buf,o,1); o+=4; put_le32(buf,o,(uint32_t)outlen); o+=4; put_le32(buf,o,(uint32_t)memory_kib); o+=4;
    put_le32(buf,o,(uint32_t)passes); o+=4; put_le32(buf,o,0x13); o+=4; put_le32(buf,o,TYPE_ID); o+=4;
    put_le32(buf,o,(uint32_t)pwdlen); o+=4; if(pwdlen) memcpy(buf+o,password,pwdlen); o+=(int)pwdlen;
    put_le32(buf,o,(uint32_t)saltlen); o+=4; memcpy(buf+o,salt,saltlen); o+=(int)saltlen;
    put_le32(buf,o,0); o+=4; put_le32(buf,o,0); o+=4;
    uint8_t h0[64]; blake2b_hash(buf,(size_t)o,64,h0); memset(buf,0,buflen); free(buf);

    uint8_t seed[72]; memcpy(seed,h0,64);
    for(int i=0;i<2;i++){
        put_le32(seed,64,(uint32_t)i); put_le32(seed,68,0);
        uint8_t b1024[1024]; hprime(seed,72,1024,b1024); bytes_to_block(b1024, mem[i]); memset(b1024,0,1024);
    }
    memset(h0,0,64);

    uint64_t r[WORDS], z[WORDS];
    for(int pass=0; pass<passes; pass++){
        for(int slice=0; slice<SYNC_POINTS; slice++){
            int data_independent = (pass==0 && slice<2);
            uint64_t input[WORDS], address[WORDS], zero[WORDS];
            if(data_independent){
                memset(input,0,sizeof(input)); memset(address,0,sizeof(address)); memset(zero,0,sizeof(zero));
                input[0]=(uint64_t)pass; input[1]=0; input[2]=(uint64_t)slice; input[3]=(uint64_t)blocks; input[4]=(uint64_t)passes; input[5]=TYPE_ID;
            }
            int start_idx=0;
            if(pass==0 && slice==0){
                start_idx=2;
                if(data_independent){ input[6]++; fill_block(zero,input,address,0,r,z); fill_block(zero,address,address,0,r,z); }
            }
            int cur = slice*segment_length + start_idx;
            for(int i=start_idx; i<segment_length; i++, cur++){
                int prev = (cur==0)? lane_length-1 : cur-1;
                uint64_t randv;
                if(data_independent){
                    if(i%WORDS==0){ input[6]++; fill_block(zero,input,address,0,r,z); fill_block(zero,address,address,0,r,z); }
                    randv = address[i%WORDS];
                } else { randv = mem[prev][0]; }
                uint64_t j1 = randv & 0xFFFFFFFFULL;
                uint64_t area;
                if(pass==0) area = (slice==0)? (uint64_t)(i-1) : (uint64_t)slice*segment_length + i - 1;
                else area = (uint64_t)lane_length - segment_length + i - 1;
                uint64_t rel = (j1*j1) >> 32;
                rel = area - 1 - ((area*rel) >> 32);
                uint64_t startp = 0;
                if(pass!=0) startp = (slice==SYNC_POINTS-1)? 0 : (uint64_t)(slice+1)*segment_length;
                int ref = (int)((startp + rel) % (uint64_t)lane_length);
                fill_block(mem[prev], mem[ref], mem[cur], pass!=0, r, z);
            }
        }
    }
    uint8_t last[1024]; block_to_bytes(mem[lane_length-1], last);
    hprime(last,1024,outlen,tag);
    memset(last,0,1024); memset(r,0,sizeof(r)); memset(z,0,sizeof(z));
    memset(mem,0,(size_t)blocks*sizeof(*mem)); free(mem);
    return 0;
}

JNIEXPORT jbyteArray JNICALL
Java_aegires_e2ee_NativeArgon2_hashNative(JNIEnv *env, jclass clazz,
        jbyteArray password, jbyteArray salt, jint passes, jint memoryKiB, jint outLen) {
    if (!salt || passes < 1 || memoryKiB < 8 || outLen < 4) return NULL;
    jsize pwdLen = password ? (*env)->GetArrayLength(env, password) : 0;
    jsize saltLen = (*env)->GetArrayLength(env, salt);
    jbyte *pwd = password ? (*env)->GetByteArrayElements(env, password, NULL) : NULL;
    jbyte *s = (*env)->GetByteArrayElements(env, salt, NULL);
    if ((password && !pwd) || !s) {
        if (pwd) (*env)->ReleaseByteArrayElements(env, password, pwd, JNI_ABORT);
        if (s) (*env)->ReleaseByteArrayElements(env, salt, s, JNI_ABORT);
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, outLen);
    if (!out) {
        if (pwd) (*env)->ReleaseByteArrayElements(env, password, pwd, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, salt, s, JNI_ABORT);
        return NULL;
    }
    uint8_t *tag = (uint8_t*)malloc((size_t)outLen);
    if (!tag) {
        if (pwd) (*env)->ReleaseByteArrayElements(env, password, pwd, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, salt, s, JNI_ABORT);
        return NULL;
    }
    int rc = argon2id_hash((const uint8_t*)pwd, (size_t)pwdLen, (const uint8_t*)s, (size_t)saltLen,
            (int)passes, (int)memoryKiB, (int)outLen, tag);
    if (rc == 0) {
        (*env)->SetByteArrayRegion(env, out, 0, outLen, (const jbyte*)tag);
    }
    memset(tag, 0, (size_t)outLen); free(tag);
    if (pwd) {
        memset(pwd, 0, (size_t)pwdLen);
        (*env)->ReleaseByteArrayElements(env, password, pwd, JNI_ABORT);
    }
    (*env)->ReleaseByteArrayElements(env, salt, s, JNI_ABORT);
    return rc == 0 ? out : NULL;
}

/* ===== SHA-256 / HMAC-SHA-256 / HKDF-SHA-256 (used for the HKDF benchmark and key schedule) ===== */
typedef struct { uint32_t state[8]; uint64_t count; uint8_t buffer[64]; } SHA256_CTX;
static const uint32_t K256[64] = {
  0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
  0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
  0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
  0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
  0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
  0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
  0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
  0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};
#define ROR32(x,n) (((x)>>(n))|((x)<<(32-(n))))
#define CH(x,y,z) (((x)&(y))^(~(x)&(z)))
#define MAJ(x,y,z) (((x)&(y))^((x)&(z))^((y)&(z)))
#define EP0(x) (ROR32(x,2)^ROR32(x,13)^ROR32(x,22))
#define EP1(x) (ROR32(x,6)^ROR32(x,11)^ROR32(x,25))
#define SIG0(x) (ROR32(x,7)^ROR32(x,18)^((x)>>3))
#define SIG1(x) (ROR32(x,17)^ROR32(x,19)^((x)>>10))
/* Portable block function (kept unchanged as the fallback and as the reference for the hardware path). */
static void sha256_transform_sw(uint32_t state[8], const uint8_t data[]){
  uint32_t a,b,c,d,e,f,g,h,i,j,t1,t2,m[64];
  for(i=0,j=0;i<16;++i,j+=4) m[i]=((uint32_t)data[j]<<24)|((uint32_t)data[j+1]<<16)|((uint32_t)data[j+2]<<8)|((uint32_t)data[j+3]);
  for(;i<64;++i) m[i]=SIG1(m[i-2])+m[i-7]+SIG0(m[i-15])+m[i-16];
  a=state[0]; b=state[1]; c=state[2]; d=state[3];
  e=state[4]; f=state[5]; g=state[6]; h=state[7];
  for(i=0;i<64;++i){ t1=h+EP1(e)+CH(e,f,g)+K256[i]+m[i]; t2=EP0(a)+MAJ(a,b,c); h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2; }
  state[0]+=a; state[1]+=b; state[2]+=c; state[3]+=d;
  state[4]+=e; state[5]+=f; state[6]+=g; state[7]+=h;
}
static void sha256_blocks_sw(uint32_t state[8], const uint8_t *data, size_t nblocks){
  while(nblocks--){ sha256_transform_sw(state, data); data += 64; }
}

#if AEGIRES_SHA256_HW
/* ARMv8 Cryptography Extensions: SHA256H / SHA256H2 / SHA256SU0 / SHA256SU1, 4 rounds per instruction pair. */
#define SHA_RND(M, k) do { uint32x4_t tmp_ = vaddq_u32((M), vld1q_u32(K256 + (k))); uint32x4_t t2_ = s0; \
    s0 = vsha256hq_u32(s0, s1, tmp_); s1 = vsha256h2q_u32(s1, t2_, tmp_); } while(0)
#define SHA_SCH(M0, M1, M2, M3) ((M0) = vsha256su1q_u32(vsha256su0q_u32((M0), (M1)), (M2), (M3)))
SHA2_TARGET
static void sha256_blocks_hw(uint32_t state[8], const uint8_t *data, size_t nblocks){
  uint32x4_t s0 = vld1q_u32(&state[0]), s1 = vld1q_u32(&state[4]);
  while(nblocks--){
    uint32x4_t abcd = s0, efgh = s1;
    uint32x4_t m0 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data)));
    uint32x4_t m1 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 16)));
    uint32x4_t m2 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 32)));
    uint32x4_t m3 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 48)));
    SHA_RND(m0,  0); SHA_SCH(m0, m1, m2, m3);
    SHA_RND(m1,  4); SHA_SCH(m1, m2, m3, m0);
    SHA_RND(m2,  8); SHA_SCH(m2, m3, m0, m1);
    SHA_RND(m3, 12); SHA_SCH(m3, m0, m1, m2);
    SHA_RND(m0, 16); SHA_SCH(m0, m1, m2, m3);
    SHA_RND(m1, 20); SHA_SCH(m1, m2, m3, m0);
    SHA_RND(m2, 24); SHA_SCH(m2, m3, m0, m1);
    SHA_RND(m3, 28); SHA_SCH(m3, m0, m1, m2);
    SHA_RND(m0, 32); SHA_SCH(m0, m1, m2, m3);
    SHA_RND(m1, 36); SHA_SCH(m1, m2, m3, m0);
    SHA_RND(m2, 40); SHA_SCH(m2, m3, m0, m1);
    SHA_RND(m3, 44); SHA_SCH(m3, m0, m1, m2);
    SHA_RND(m0, 48);
    SHA_RND(m1, 52);
    SHA_RND(m2, 56);
    SHA_RND(m3, 60);
    s0 = vaddq_u32(s0, abcd); s1 = vaddq_u32(s1, efgh);
    data += 64;
  }
  vst1q_u32(&state[0], s0); vst1q_u32(&state[4], s1);
}
#endif

/* 1 when the hardware block function is present AND matches the portable one on a test input, else 0.
 * Decided once; a race between threads only repeats the same idempotent check. */
static volatile int sha256_hw_state = -1;
static int sha256_hw_ok(void){
  int v = sha256_hw_state;
  if (v >= 0) return v;
  v = 0;
#if AEGIRES_SHA256_HW
  if (getauxval(AT_HWCAP) & HWCAP_SHA2) {
    uint8_t blk[3 * 64];
    uint32_t a[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    uint32_t b[8];
    for (int i = 0; i < (int)sizeof blk; i++) blk[i] = (uint8_t)(i * 131 + 7);
    memcpy(b, a, sizeof a);
    sha256_blocks_sw(a, blk, 3);
    sha256_blocks_hw(b, blk, 3);
    v = (memcmp(a, b, sizeof a) == 0) ? 1 : 0;
  }
#endif
  sha256_hw_state = v;
  return v;
}

static void sha256_blocks(SHA256_CTX *ctx, const uint8_t *data, size_t nblocks){
#if AEGIRES_SHA256_HW
  if (sha256_hw_ok()) { sha256_blocks_hw(ctx->state, data, nblocks); return; }
#endif
  sha256_blocks_sw(ctx->state, data, nblocks);
}
static void sha256_transform(SHA256_CTX *ctx, const uint8_t data[]){ sha256_blocks(ctx, data, 1); }

static void sha256_init(SHA256_CTX *ctx){
  ctx->count=0;
  ctx->state[0]=0x6a09e667; ctx->state[1]=0xbb67ae85; ctx->state[2]=0x3c6ef372; ctx->state[3]=0xa54ff53a;
  ctx->state[4]=0x510e527f; ctx->state[5]=0x9b05688c; ctx->state[6]=0x1f83d9ab; ctx->state[7]=0x5be0cd19;
}
static void sha256_update(SHA256_CTX *ctx, const uint8_t *data, size_t len){
  size_t fill = (size_t)(ctx->count % 64);
  ctx->count += len;
  if(fill){
    size_t n = 64 - fill; if(n > len) n = len;
    memcpy(ctx->buffer + fill, data, n); data += n; len -= n; fill += n;
    if(fill < 64) return;
    sha256_transform(ctx, ctx->buffer);
  }
  if(len >= 64){ size_t nb = len / 64; sha256_blocks(ctx, data, nb); data += nb * 64; len -= nb * 64; }
  if(len) memcpy(ctx->buffer, data, len);
}
static void sha256_final(SHA256_CTX *ctx, uint8_t hash[32]){
  uint64_t bits = ctx->count * 8; size_t i = ctx->count % 64; ctx->buffer[i++] = 0x80;
  if(i > 56){ while(i<64) ctx->buffer[i++]=0; sha256_transform(ctx, ctx->buffer); i=0; }
  while(i<56) ctx->buffer[i++]=0;
  for(int j=7;j>=0;--j) ctx->buffer[i++] = (uint8_t)(bits >> (j*8));
  sha256_transform(ctx, ctx->buffer);
  for(i=0;i<4;++i) for(int j=0;j<8;++j) hash[i+j*4] = (uint8_t)((ctx->state[j] >> (24-i*8)) & 0xff);
}

/* HMAC with the two key-dependent blocks (ipad / opad) hashed once: HKDF-Expand reuses the same PRK for
 * every output block, so this saves two compressions per HMAC call after the first. */
typedef struct { SHA256_CTX in, out; } HMAC_CTX;
static void hmac_init(HMAC_CTX *h, const uint8_t *key, size_t keylen){
  uint8_t k_ipad[64], k_opad[64], tk[32]; const uint8_t *k = key; size_t klen = keylen;
  if(klen>64){ SHA256_CTX tctx; sha256_init(&tctx); sha256_update(&tctx,key,klen); sha256_final(&tctx,tk); k=tk; klen=32; memset(&tctx,0,sizeof tctx); }
  memset(k_ipad,0,64); memset(k_opad,0,64); memcpy(k_ipad,k,klen); memcpy(k_opad,k,klen);
  for(int i=0;i<64;i++){ k_ipad[i]^=0x36; k_opad[i]^=0x5c; }
  sha256_init(&h->in);  sha256_update(&h->in,k_ipad,64);
  sha256_init(&h->out); sha256_update(&h->out,k_opad,64);
  memset(k_ipad,0,64); memset(k_opad,0,64); memset(tk,0,32);
}
static void hmac_run(const HMAC_CTX *h, const uint8_t *data, size_t datalen, uint8_t out[32]){
  SHA256_CTX ctx; uint8_t inner[32];
  ctx = h->in;  sha256_update(&ctx,data,datalen); sha256_final(&ctx,inner);
  ctx = h->out; sha256_update(&ctx,inner,32);     sha256_final(&ctx,out);
  memset(&ctx,0,sizeof ctx); memset(inner,0,32);
}
static void hmac_sha256(const uint8_t *key,size_t keylen,const uint8_t *data,size_t datalen,uint8_t out[32]){
  HMAC_CTX h; hmac_init(&h,key,keylen); hmac_run(&h,data,datalen,out); memset(&h,0,sizeof h);
}

/* HKDF-SHA-256 (RFC 5869): extract with `salt`, expand to `len` bytes (len <= 255 * 32). */
static void hkdf_sha256(const uint8_t *salt, size_t sl, const uint8_t *ikm, size_t il,
                        const uint8_t *info, size_t infol, uint8_t *okm, size_t len, uint8_t *work){
  uint8_t prk[32], t[32]; size_t tlen = 0, pos = 0; uint8_t ctr = 1;
  static const uint8_t zeros[32] = {0};
  HMAC_CTX h;
  if (sl == 0) { salt = zeros; sl = 32; }
  hmac_sha256(salt, sl, ikm, il, prk);
  hmac_init(&h, prk, 32);
  while (pos < len) {
    size_t inlen = tlen + infol + 1;       /* work holds T(i-1) | info | counter */
    if (tlen) memcpy(work, t, tlen);
    if (infol) memcpy(work + tlen, info, infol);
    work[inlen - 1] = ctr;
    hmac_run(&h, work, inlen, t);
    tlen = 32;
    size_t n = (len - pos < 32) ? len - pos : 32;
    memcpy(okm + pos, t, n); pos += n; ctr++;
  }
  memset(prk, 0, 32); memset(t, 0, 32); memset(&h, 0, sizeof h);
}

/* boolean sha256HwNative(): true when HKDF/HMAC/SHA-256 run on the ARMv8 SHA-2 instructions in this process. */
JNIEXPORT jboolean JNICALL
Java_aegires_e2ee_NativeArgon2_sha256HwNative(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return sha256_hw_ok() ? JNI_TRUE : JNI_FALSE;
}

/*
 * byte[] hkdfNative(byte[] salt, byte[] ikm, byte[] info, int len, int iterations)
 * Runs extract+expand `iterations` times on the same input (1 for real use, many for the benchmark,
 * so the JNI call is not what gets timed) and returns the last output. NULL for bad arguments.
 */
JNIEXPORT jbyteArray JNICALL
Java_aegires_e2ee_NativeArgon2_hkdfNative(JNIEnv *env, jclass clazz,
        jbyteArray salt, jbyteArray ikm, jbyteArray info, jint len, jint iterations) {
    (void)clazz;
    if (!salt || !ikm || !info || len < 1 || len > 255 * 32 || iterations < 1) return NULL;
    jsize sl = (*env)->GetArrayLength(env, salt), il = (*env)->GetArrayLength(env, ikm), nl = (*env)->GetArrayLength(env, info);
    jbyte *sb = (*env)->GetByteArrayElements(env, salt, NULL);
    jbyte *ib = (*env)->GetByteArrayElements(env, ikm, NULL);
    jbyte *nb = (*env)->GetByteArrayElements(env, info, NULL);
    uint8_t *okm = (uint8_t*)malloc((size_t)len);
    uint8_t *work = (uint8_t*)malloc((size_t)nl + 33);
    jbyteArray out = NULL;
    if (sb && ib && nb && okm && work) {
        for (jint k = 0; k < iterations; k++)
            hkdf_sha256((const uint8_t*)sb, (size_t)sl, (const uint8_t*)ib, (size_t)il,
                        (const uint8_t*)nb, (size_t)nl, okm, (size_t)len, work);
        out = (*env)->NewByteArray(env, len);
        if (out) (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte*)okm);
    }
    if (okm) { memset(okm, 0, (size_t)len); free(okm); }
    if (work) { memset(work, 0, (size_t)nl + 33); free(work); }
    if (sb) (*env)->ReleaseByteArrayElements(env, salt, sb, JNI_ABORT);
    if (ib) (*env)->ReleaseByteArrayElements(env, ikm, ib, JNI_ABORT);
    if (nb) (*env)->ReleaseByteArrayElements(env, info, nb, JNI_ABORT);
    return out;
}
