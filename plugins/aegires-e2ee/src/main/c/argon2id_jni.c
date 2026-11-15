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

static inline uint64_t mix64(uint64_t x,uint64_t y){ uint64_t lo=(x&0xFFFFFFFFULL)*(y&0xFFFFFFFFULL); return x+y+2*lo; }
static void g_a(uint64_t v[WORDS], int a,int b,int c,int d){
    v[a]=mix64(v[a],v[b]); v[d]=rotr64(v[d]^v[a],32);
    v[c]=mix64(v[c],v[d]); v[b]=rotr64(v[b]^v[c],24);
    v[a]=mix64(v[a],v[b]); v[d]=rotr64(v[d]^v[a],16);
    v[c]=mix64(v[c],v[d]); v[b]=rotr64(v[b]^v[c],63);
}
static void p_a(uint64_t v[WORDS], int i0,int i1,int i2,int i3,int i4,int i5,int i6,int i7,int i8,int i9,int i10,int i11,int i12,int i13,int i14,int i15){
    g_a(v,i0,i4,i8,i12); g_a(v,i1,i5,i9,i13); g_a(v,i2,i6,i10,i14); g_a(v,i3,i7,i11,i15);
    g_a(v,i0,i5,i10,i15); g_a(v,i1,i6,i11,i12); g_a(v,i2,i7,i8,i13); g_a(v,i3,i4,i9,i14);
}
static void fill_block(uint64_t *x,uint64_t *y,uint64_t *out,int xorout,uint64_t r[WORDS],uint64_t z[WORDS]){
    for(int i=0;i<WORDS;i++){ r[i]=x[i]^y[i]; z[i]=r[i]; }
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
