/*
 * rastertolhpl — Lenovo M7208W (LHPL/HBPL2 方言) 原生 arm64 CUPS 过滤器
 *
 * 替代 Intel-only 的 /usr/libexec/cups/filter/LNTHR1Z (Rosetta 退役后的原生续命)
 * 协议格式经逆向 + golden 逐字节验证(见 NOTES.md):
 *   PJL 头(语义全解) + LHPL 块(ESC LH@ sj/sp/ep, 64B头+XOR校验)
 *   + JBIG (jbigkit 2.1: XD=5120 画布, YD=页高, L0=128, layers=0,
 *     order=0, options=0x340, mx=8,  my=0) — 已验证与 Intel 编码器逐字节一致
 *
 * 与 Intel 差异: 灰度→位图抖动用 Floyd–Steinberg 替代 dylib 的 NTDCMS 有序屏
 * (输出仍为合法 LHPL 流; 位图内容等价, 字节不逐位相同)
 *
 * 用法: 与原 LNTHR1Z 相同的 CUPS 过滤器接口 (stdin= raster, stdout=打印机流)
 */
#include <cups/raster.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <stdarg.h>
#include <fcntl.h>
#include <unistd.h>
#include "jbig.h"

#define CANVAS_W 5120          /* 600dpi Letter 满幅画布宽 (Intel 固定值) */
#define CANVAS_BPL (CANVAS_W/8)
#define LH_MAGIC "\x1bLH@"

static FILE *g_out;
static unsigned long g_outcount;

static void emit(const void *p, size_t n){ fwrite(p,1,n,g_out); g_outcount+=n; }
static void emitf(const char *fmt, ...){
    char buf[512]; va_list ap; va_start(ap,fmt);
    int n=vsnprintf(buf,sizeof buf,fmt,ap); va_end(ap);
    emit(buf,n);
}

/* ---------- LHPL 块 ---------- */
/* 块 = 6B 头(ESC LH@ + 2字符命令) + 载荷 + 尾部补零到 64B + [63]=前63字节XOR */
static void write_chunk(const char cmd[2], const unsigned char *payload, int paylen)
{
    unsigned char blk[64];
    memset(blk,0,64);
    blk[0]=0x1b; blk[1]='L'; blk[2]='H'; blk[3]='@'; blk[4]=cmd[0]; blk[5]=cmd[1];
    if(paylen>57) paylen=57;   /* 载荷57B+XOR@63; 传58会静默吃掉末字节(审查6) */
    if(payload&&paylen) memcpy(blk+6,payload,paylen);
    unsigned char x=0; for(int i=0;i<63;i++) x^=blk[i];
    blk[63]=x;
    emit(blk,64);
}

/* ---------- JBIG ---------- */
struct jbgbuf { unsigned char *d; size_t n, cap; };
static void jbig_out(unsigned char *start, size_t len, void *priv){
    struct jbgbuf *b=priv;
    if(b->n+len>b->cap){ b->cap=(b->n+len)*2;
        void *t=realloc(b->d,b->cap);
        if(!t){ fprintf(stderr,"ERROR: jbigbuf realloc\n"); exit(1); }
        b->d=t; }
    memcpy(b->d+b->n,start,len); b->n+=len;
}
/* 位图(1bpp, CANVAS_BPL×h 行) → BIE 字节串 */
static struct jbgbuf jbig_encode(unsigned char *bmp, unsigned long h)
{
    struct jbgbuf b={0}; struct jbg_enc_state s;
    unsigned char *rows[1]={bmp};
    jbg_enc_init(&s, CANVAS_W, h, 1, rows, jbig_out, &b);
    jbg_enc_layers(&s, 0);
    jbg_enc_options(&s, 0, 0x340, 128, 8, 0);   /* MX=8 手机/固件样本同 */
    jbg_enc_out(&s);
    jbg_enc_free(&s);
    return b;
}

/* ---------- Floyd–Steinberg 抖动 (8bpp 灰 → 1bpp, 白=0 黑=1) ---------- */
/* top-down 填行: 画布第 0 行 = 页面第 0 行 (10-16 实测定案, bottom-up 是错的) */
static void dither_to_canvas(cups_page_header2_t *h, cups_raster_t *ras,
                             unsigned char *canvas)
{
    int w=h->cupsWidth, H=h->cupsHeight, bpl=h->cupsBytesPerLine;
    int *cur=calloc(w,sizeof(int)), *next=calloc(w,sizeof(int));
    unsigned char *line=malloc(bpl);
    if(!cur||!next||!line){ fprintf(stderr,"ERROR: dither OOM\n"); exit(1); }
    for(int y_in=0; y_in<H; y_in++){
        if((int)cupsRasterReadPixels(ras,line,bpl)!=bpl){ fprintf(stderr,"ERROR: 截断光栅流\n"); exit(1); }
        unsigned char *row=canvas+y_in*CANVAS_BPL;
        memset(row,0,CANVAS_BPL);
        for(int x=0;x<w;x++){
            /* CUPS 1bpp约定: 置位=黑; 8bpp约定: 0=黑255=白(抖动按255=白判) */
            int g = (h->cupsBitsPerPixel==8)? line[x] : ((line[x/8]>>(7-x%8)&1)? 0 : 255);
            int v = g + cur[x];
            int bit = (v<128);            /* 暗→置位(黑); CUPS灰阶255=白 */
            if(x+1<w) cur[x+1]+= (bit? v : v-255)*7/16;
            if(x>0)  next[x-1]+= (bit? v : v-255)*3/16;
            next[x]  += (bit? v : v-255)*5/16;
            if(x+1<w) next[x+1]+= (bit? v : v-255)*1/16;
            if(bit && x<CANVAS_W) row[x/8]|=0x80>>(x%8);
        }
        memset(cur,0,w*sizeof(int));
        int *t=cur; cur=next; next=t;
    }
    free(line); free(cur); free(next);
}

/* ---------- PJL ---------- */
/* 头序逐字节对齐固件 1.3.00 内嵌合法样本(fw_main_inflated 0x84bc62, 2026-10-18 挖出):
 * MDPXS=0(非枚举值), 无 PCNT(仅双面作业才需要); 头预算≤1024B, 行尾必须 \r\n */
static int g_copies=1;
static void write_pjl_header(cups_page_header2_t *h, const char *user, const char *title)
{
    (void)user; (void)title;
    /* 手机驱动 lenovogdiPJLHead 模板D(单面)逐字节复刻: 仅5行SET, DUPLEX=带空格 */
    emitf("\x1b%%-12345X@PJL JOB NAME=PRINTER\r\n");
    emitf("@PJL SET RESOLUTION=%d\r\n", h->HWResolution[1]);
    emitf("@PJL SET BITSPERPIXEL=1\r\n");
    emitf("@PJL SET RENDERMODE=GRAYSCALE\r\n");
    emitf("@PJL SET COPIES=%d\r\n", g_copies);   /* 协议原生份数(审查2: cupsManualCopies=True要求过滤器自己产) */
    emitf("@PJL SET DUPLEX = OFF\r\n");
    emitf("@PJL ENTER LANGUAGE=LHPL\r\n");
}
static void write_pjl_footer(void)
{
    emitf("\x1b%%-12345X@PJL EOJ\r\n");
}

/* ---------- sp 块 (载荷 58B + JBIG blob) ---------- */
/* 字段语义 2026-10-18 定案(golden+手机驱动+固件样本三方): pay[10..13]=LE32(⌊5120/8⌋×h)
 * (手机驱动公式; 固件按此分配引擎内存); pay[14..17]=LE32(总长); pay[18..21]=LE32(slot0=总长) */
static void put32(unsigned char *p, unsigned long v)
{ p[0]=v&0xff; p[1]=(v>>8)&0xff; p[2]=(v>>16)&0xff; p[3]=(v>>24)&0xff; }

static void write_sp(unsigned long page_h, struct jbgbuf *bies[4], int paper_letter)
{
    size_t total=0;
    for(int i=0;i<4;i++) if(bies && bies[i]) total+=bies[i]->n;
    unsigned char pay[58]; memset(pay,0,58);
    pay[0]=paper_letter?1:2; pay[1]=1;             /* [6]=纸码: 1=Letter 2=A4(按页尺寸映射, 审查3); [7]=介质1 */
    put32(pay+2, 5120);                            /* [8..11] 宽 */
    put32(pay+6, page_h);                          /* [12..15] 页高 */
    put32(pay+10, (5120/8)*page_h);                /* [16..19] =⌊XD/8⌋×YD (手机驱动公式) */
    put32(pay+14, total);                          /* [20..23] JBIG总长 */
    put32(pay+18, total);                          /* [24..27] slot0=总长(手机: 总长写两遍) */
    /* slots1-3 = 0 已由memset */
    pay[36]=600&0xff; pay[37]=600>>8;
    pay[38]=paper_letter?2159&0xff:2100&0xff; pay[39]=paper_letter?2159>>8:2100>>8;
    pay[40]=paper_letter?2794&0xff:2970&0xff; pay[41]=paper_letter?2794>>8:2970>>8;
    write_chunk("sp",pay,58);
    for(int i=0;i<4;i++) if(bies&&bies[i]&&bies[i]->n) emit(bies[i]->d,bies[i]->n);
}

int main(int argc, char **argv)
{
    if(argc<6){ fprintf(stderr,"Usage: %s job user title copies options [file]\n",argv[0]); return 1; }
    cups_raster_t *ras=cupsRasterOpen(argc==7?open(argv[6],O_RDONLY):0 /*stdin*/,CUPS_RASTER_READ);
    if(!ras){ fprintf(stderr,"ERROR: cupsRasterOpen\n"); return 1; }
    g_out=stdout;
    g_copies=(argc>4)? atoi(argv[4]) : 1;
    if(g_copies<1) g_copies=1; if(g_copies>99) g_copies=99;
    cups_page_header2_t h; int first=1;
    while(cupsRasterReadHeader2(ras,&h)){
        if(first){ write_pjl_header(&h,argv[2],argv[3]);
                   unsigned char sjpay[4]={1,0,1,0};
                   write_chunk("sj",sjpay,4); first=0; }
        /* 画布 + 抖动 */
        unsigned char *canvas=calloc(h.cupsHeight,CANVAS_BPL);
        if(!canvas){ fprintf(stderr,"ERROR: canvas OOM (%lux%lu)\n",
                             (unsigned long)h.cupsHeight,(unsigned long)CANVAS_BPL); return 1; }
        dither_to_canvas(&h,ras,canvas);
        /* JBIG: 单K平面全高(手机mono路线: slot0=总长, 槽2-4=0) */
        struct jbgbuf b0=jbig_encode(canvas,h.cupsHeight);
        struct jbgbuf *bies[4]={&b0,NULL,NULL,NULL};
        /* 按页宽判纸型: Letter=612pt A4=595pt(±5容差); 固件sp[0x2a]只认600, 其他dpi一律按600发(审查3) */
        int letter = (h.cupsPageSize[0] > 603);
        write_sp(h.cupsHeight,bies,letter);
        write_chunk("ep",NULL,0);
        fprintf(stderr,"PAGE: 1 1\n");
        free(canvas); free(b0.d);
    }
    cupsRasterClose(ras);
    if(first){ fprintf(stderr,"ERROR: 0页/坏光栅流\n"); return 1; }   /* 不吐孤儿EOJ(审查7) */
    write_pjl_footer();
    if(fflush(g_out)!=0){ fprintf(stderr,"ERROR: flush\n"); return 1; }
    return 0;
}
