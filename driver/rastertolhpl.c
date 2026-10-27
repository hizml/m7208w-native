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
 * 作业选项(argv[5], 空格分隔 key=value; PPD 已声明同名UI, 系统打印对话框可直选):
 *   Density=-3..3  浓度(曲线 f=1+0.12d, 手机端 bitmapToCanvas 同公式)
 *   TonerSave=True/False  省墨(抖动阈值 128→98)
 *   Nup=1/2/4/9/16  N合1(2=上下两格, 4/9/16=n×n, 0.92留边, 手机端 composeNup 同规则)
 */
#include <cups/raster.h>
#include <math.h>
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

/* ---------- 作业选项 ---------- */
#define DENSITY_MIN (-3)
#define DENSITY_MAX 3
#define NUP_CELL_MARGIN 0.92   /* N合1子页留边(手机端 composeNup 同值) */
#define TONERSAVE_THRESHOLD 98 /* 省墨阈值: 相对标准128下移=着墨减少 */
#define STANDARD_THRESHOLD 128

static int g_density = 0;      /* 浓度 -3..3, 0=不调整 */
static int g_tonersave = 0;    /* 省墨开关 */
static int g_nup = 1;          /* N合1: 每n页拼一张纸 */

static int clampi(int v, int lo, int hi){ return v<lo?lo : v>hi?hi:v; }

/* argv[5] 形如 "Density=3 TonerSave=True Nup=4" (CUPS 汇总本作业全部 -o 选项) */
static void parse_job_options(const char *opts)
{
    if(!opts || !*opts) return;
    char buf[2048];
    snprintf(buf,sizeof buf,"%s",opts);
    for(char *tok=strtok(buf," \t"); tok; tok=strtok(NULL," \t")){
        char val[64];
        if(sscanf(tok,"Density=%63s",val)==1)
            g_density=clampi((int)strtol(val,NULL,10),DENSITY_MIN,DENSITY_MAX);
        else if(sscanf(tok,"TonerSave=%63s",val)==1)
            g_tonersave=(strcmp(val,"True")==0||strcmp(val,"true")==0||strcmp(val,"1")==0);
        else if(sscanf(tok,"Nup=%63s",val)==1){
            int n=(int)strtol(val,NULL,10);
            g_nup=(n==2||n==4||n==9||n==16)? n : 1;
        }
    }
    fprintf(stderr,"INFO: options Density=%d TonerSave=%d Nup=%d\n",
            g_density,g_tonersave,g_nup);
}

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

/* ---------- 灰度化与抖动 ---------- */
/* 整页光栅 → 8bpp灰度缓冲(w×h); 8bpp直取, 1bpp展开(置位=黑0, 未置位=白255) */
static unsigned char *read_page_gray(cups_raster_t *ras, cups_page_header2_t *h)
{
    int w=h->cupsWidth, H=h->cupsHeight, bpl=h->cupsBytesPerLine;
    if(w<=0||H<=0||bpl<=0){ fprintf(stderr,"ERROR: 坏页头 %dx%d\n",w,H); return NULL; }
    unsigned char *gray=malloc((size_t)w*H), *line=malloc(bpl);
    if(!gray||!line){ fprintf(stderr,"ERROR: page OOM\n"); exit(1); }
    for(int y=0;y<H;y++){
        if((int)cupsRasterReadPixels(ras,line,bpl)!=bpl){
            fprintf(stderr,"ERROR: 截断光栅流\n"); free(gray); free(line); return NULL;
        }
        unsigned char *dst=gray+(size_t)y*w;
        if(h->cupsBitsPerPixel==8) memcpy(dst,line,(size_t)w);
        else for(int x=0;x<w;x++) dst[x]=((line[x/8]>>(7-x%8))&1)? 0 : 255;
    }
    free(line);
    return gray;
}

/* 灰度缓冲 → 5120宽1bpp画布, top-down 填行(画布第0行=页面第0行, 10-16 实测定案)
 * FS误差扩散 + 浓度曲线(f=1+0.12d, 手机端同公式) + 省墨阈值(128/98);
 * 默认参数(密度0/不省墨)与旧版逐字节一致 */
static void dither_gray_to_canvas(const unsigned char *gray, int w, int H,
                                  unsigned char *canvas)
{
    int *cur=calloc((size_t)w,sizeof(int)), *next=calloc((size_t)w,sizeof(int));
    if(!cur||!next){ fprintf(stderr,"ERROR: dither OOM\n"); exit(1); }
    double f=1.0+0.12*g_density, bias=(255.0-255.0*f)/2.0;
    int thr=g_tonersave? TONERSAVE_THRESHOLD : STANDARD_THRESHOLD;
    for(int y=0;y<H;y++){
        const unsigned char *src=gray+(size_t)y*w;
        unsigned char *row=canvas+(size_t)y*CANVAS_BPL;
        memset(row,0,CANVAS_BPL);
        for(int x=0;x<w;x++){
            int g=src[x];
            if(g_density) g=clampi((int)(g*f+bias),0,255);
            int v=g+cur[x];
            int bit=(v<thr);           /* 暗→置位(黑); CUPS灰阶255=白 */
            if(x+1<w) cur[x+1]+= (bit? v : v-255)*7/16;
            if(x>0)  next[x-1]+= (bit? v : v-255)*3/16;
            next[x]  += (bit? v : v-255)*5/16;
            if(x+1<w) next[x+1]+= (bit? v : v-255)*1/16;
            if(bit && x<CANVAS_W) row[x/8]|=0x80>>(x%8);
        }
        memset(cur,0,(size_t)w*sizeof(int));
        int *t=cur; cur=next; next=t;
    }
    free(cur); free(next);
}

/* ---------- N合1 拼版 ---------- */
/* 网格: 2=上下两格, 4/9/16=n×n (手机端 composeNup 同规则) */
static void nup_grid(int n, int *cols, int *rows)
{
    static const struct { int n, c, r; } TAB[]={{2,1,2},{4,2,2},{9,3,3},{16,4,4}};
    *cols=*rows=1;
    for(size_t i=0;i<sizeof TAB/sizeof TAB[0];i++)
        if(TAB[i].n==n){ *cols=TAB[i].c; *rows=TAB[i].r; }
}

/* 子页灰度按面积平均缩放进格子(居中, 0.92留边); 放大时退化为最近邻 */
static void compose_cell(const unsigned char *src, int sw, int sh,
                         unsigned char *sheet, int sheet_w,
                         int cw, int ch, int cell_x, int cell_y)
{
    double s=fmin(cw*NUP_CELL_MARGIN/sw, ch*NUP_CELL_MARGIN/sh);
    int tw=clampi((int)(sw*s),1,cw), th=clampi((int)(sh*s),1,ch);
    int off_x=cell_x+(cw-tw)/2, off_y=cell_y+(ch-th)/2;
    for(int dy=0;dy<th;dy++){
        int y0=(int)((long)dy*sh/th), y1=(int)((long)(dy+1)*sh/th);
        if(y1<=y0) y1=y0+1;
        unsigned char *dst=sheet+(size_t)(off_y+dy)*sheet_w+off_x;
        for(int dx=0;dx<tw;dx++){
            int x0=(int)((long)dx*sw/tw), x1=(int)((long)(dx+1)*sw/tw);
            if(x1<=x0) x1=x0+1;
            long sum=0;
            for(int y=y0;y<y1;y++)
                for(int x=x0;x<x1;x++) sum+=src[(size_t)y*sw+x];
            dst[dx]=(unsigned char)((sum+(y1-y0)*(x1-x0)/2)/((y1-y0)*(x1-x0)));
        }
    }
}

/* 以组首页几何为纸面白底, 顺序拼入本组后续页(不足n页按已有页出纸);
 * 接管 first_gray 的释放; 返回拼好的纸面灰度缓冲 */
static unsigned char *assemble_sheet(cups_raster_t *ras, cups_page_header2_t *h,
                                     unsigned char *first_gray, int *npages)
{
    int w=h->cupsWidth, H=h->cupsHeight, cols, rows;
    nup_grid(g_nup,&cols,&rows);
    int cw=clampi(w/cols,1,w), ch=clampi(H/rows,1,H);
    unsigned char *sheet=malloc((size_t)w*H);
    if(!sheet){ fprintf(stderr,"ERROR: sheet OOM\n"); exit(1); }
    memset(sheet,255,(size_t)w*H);
    compose_cell(first_gray,h->cupsWidth,h->cupsHeight,sheet,w,cw,ch,0,0);
    free(first_gray);
    cups_page_header2_t sub;
    for(int i=1;i<g_nup && cupsRasterReadHeader2(ras,&sub);i++){
        unsigned char *g=read_page_gray(ras,&sub);
        if(!g) break;
        (*npages)++;
        compose_cell(g,sub.cupsWidth,sub.cupsHeight,sheet,w,cw,ch,(i%cols)*cw,(i/cols)*ch);
        free(g);
    }
    return sheet;
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
    emitf("@PJL SET COPIES=1\r\n");   /* 定案10-19: Mac打印会话按manualCopies复制页, 协议层恒1防乘法叠加(4张事故); 安卓走自家COPIES */
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
    parse_job_options(argv[5]);
    cups_raster_t *ras=cupsRasterOpen(argc==7?open(argv[6],O_RDONLY):0 /*stdin*/,CUPS_RASTER_READ);
    if(!ras){ fprintf(stderr,"ERROR: cupsRasterOpen\n"); return 1; }
    g_out=stdout;
    g_copies=(argc>4)? atoi(argv[4]) : 1;
    fprintf(stderr,"INFO: LNTHR1Z copies-arg=%d\n", g_copies);
    if(g_copies<1) g_copies=1; if(g_copies>99) g_copies=99;
    cups_page_header2_t h; int first=1, npages=0, nsheets=0;
    while(cupsRasterReadHeader2(ras,&h)){
        if(first){ write_pjl_header(&h,argv[2],argv[3]);
                   unsigned char sjpay[4]={1,0,1,0};
                   write_chunk("sj",sjpay,4); first=0; }
        /* 整页灰度 → (N合1时拼组) → 抖动上画布 */
        int w=h.cupsWidth, H=h.cupsHeight;
        unsigned char *gray=read_page_gray(ras,&h);
        if(!gray) return 1;
        npages++;
        if(g_nup>1) gray=assemble_sheet(ras,&h,gray,&npages);
        unsigned char *canvas=calloc(H,CANVAS_BPL);
        if(!canvas){ fprintf(stderr,"ERROR: canvas OOM (%lux%lu)\n",
                             (unsigned long)h.cupsHeight,(unsigned long)CANVAS_BPL); return 1; }
        dither_gray_to_canvas(gray,w,H,canvas);
        free(gray);
        /* JBIG: 单K平面全高(手机mono路线: slot0=总长, 槽2-4=0) */
        struct jbgbuf b0=jbig_encode(canvas,H);
        struct jbgbuf *bies[4]={&b0,NULL,NULL,NULL};
        /* 按页宽判纸型: Letter=612pt A4=595pt(±5容差); 固件sp[0x2a]只认600, 其他dpi一律按600发(审查3) */
        int letter = (h.cupsPageSize[0] > 603);
        write_sp(H,bies,letter);
        write_chunk("ep",NULL,0);
        fprintf(stderr,"PAGE: 1 1\n"); nsheets++;
        free(canvas); free(b0.d);
    }
    cupsRasterClose(ras);
    fprintf(stderr,"INFO: LNTHR1Z done pages=%d sheets=%d copies=%d\n", npages, nsheets, g_copies);
    if(first){ fprintf(stderr,"ERROR: 0页/坏光栅流\n"); return 1; }   /* 不吐孤儿EOJ(审查7) */
    write_pjl_footer();
    if(fflush(g_out)!=0){ fprintf(stderr,"ERROR: flush\n"); return 1; }
    return 0;
}
