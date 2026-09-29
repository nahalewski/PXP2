/*
 * PXP2 test plugin "vshtest" (VSH, kernel mode) - original code, GPL-3.0-or-later.
 *
 * Exercises sctrlHENFindFunction + sctrlHENPatchSyscall: it looks up sceDisplaySetFrameBuf, redirects the user-mode
 * imports of it to a hook, and the hook draws "PXP2 VSH PLUGIN OK" plus a frame counter into the framebuffer that
 * is about to be shown, then calls the original function.
 */
#include <pspsdk.h>
#include <pspkernel.h>
#include <systemctrl.h>

PSP_MODULE_INFO("PXP2_VshTest", 0x1000, 1, 0);

#include <pspkdebug.h>

/* 5x7 glyphs, one byte per row, bit 4 = leftmost pixel. Drawn for this plugin; covers the characters it prints. */
static const char glyph_chars[] = " PXVSHLUGINOK0123456789";
static const unsigned char glyphs[][7] = {
	{0, 0, 0, 0, 0, 0, 0},
	{0x1e, 0x11, 0x11, 0x1e, 0x10, 0x10, 0x10}, /* P */
	{0x11, 0x11, 0x0a, 0x04, 0x0a, 0x11, 0x11}, /* X */
	{0x11, 0x11, 0x11, 0x11, 0x11, 0x0a, 0x04}, /* V */
	{0x0f, 0x10, 0x10, 0x0e, 0x01, 0x01, 0x1e}, /* S */
	{0x11, 0x11, 0x11, 0x1f, 0x11, 0x11, 0x11}, /* H */
	{0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1f}, /* L */
	{0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0e}, /* U */
	{0x0e, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0f}, /* G */
	{0x0e, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0e}, /* I */
	{0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11}, /* N */
	{0x0e, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0e}, /* O */
	{0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11}, /* K */
	{0x0e, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0e}, /* 0 */
	{0x04, 0x0c, 0x04, 0x04, 0x04, 0x04, 0x0e}, /* 1 */
	{0x0e, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1f}, /* 2 */
	{0x1f, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0e}, /* 3 */
	{0x02, 0x06, 0x0a, 0x12, 0x1f, 0x02, 0x02}, /* 4 */
	{0x1f, 0x10, 0x1e, 0x01, 0x01, 0x11, 0x0e}, /* 5 */
	{0x06, 0x08, 0x10, 0x1e, 0x11, 0x11, 0x0e}, /* 6 */
	{0x1f, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08}, /* 7 */
	{0x0e, 0x11, 0x11, 0x0e, 0x11, 0x11, 0x0e}, /* 8 */
	{0x0e, 0x11, 0x11, 0x0f, 0x01, 0x02, 0x0c}, /* 9 */
};

static int (*orig_set_frame_buf)(void *top, int width, int format, int sync);
static unsigned int frames;

static void put_pixel(unsigned int base, int width, int format, int x, int y, int on)
{
	if (format == 3) { /* 8888 */
		volatile unsigned int *p = (unsigned int *)(base + (y * width + x) * 4);
		*p = on ? 0xffffffff : 0xff400000; /* white on dark blue */
	} else {           /* 16-bit formats: all ones is white in each of them */
		volatile unsigned short *p = (unsigned short *)(base + (y * width + x) * 2);
		*p = on ? 0xffff : 0x0000;
	}
}

static void draw_text(unsigned int base, int width, int format, int x0, int y0, const char *s)
{
	for (; *s; s++, x0 += 12) {
		int g = 0, row, col;
		for (int i = 0; glyph_chars[i]; i++) if (glyph_chars[i] == *s) { g = i; break; }
		for (row = -1; row < 8; row++)
			for (col = -1; col < 6; col++) {
				int on = row >= 0 && row < 7 && col >= 0 && col < 5 && (glyphs[g][row] >> (4 - col)) & 1;
				put_pixel(base, width, format, x0 + col * 2, y0 + row * 2, on);
				put_pixel(base, width, format, x0 + col * 2 + 1, y0 + row * 2, on);
				put_pixel(base, width, format, x0 + col * 2, y0 + row * 2 + 1, on);
				put_pixel(base, width, format, x0 + col * 2 + 1, y0 + row * 2 + 1, on);
			}
	}
}

static int hook_set_frame_buf(void *top, int width, int format, int sync)
{
	if (top != 0 && width >= 256) {
		char digits[12]; unsigned int n = ++frames; int i = 10;
		digits[11] = 0;
		do { digits[i--] = '0' + n % 10; n /= 10; } while (n && i >= 0);
		unsigned int base = ((unsigned int)top & 0x1fffffff) | 0x40000000; /* uncached mirror */
		draw_text(base, width, format, 8, 8, "PXP2 VSH PLUGIN OK");
		draw_text(base, width, format, 8, 28, digits + i + 1);
		if (frames == 1 || frames % 600 == 0) Kprintf("vshtest: drew overlay on frame %u (fb %08x, width %d, format %d)\n", frames, (unsigned int)top, width, format);
	}
	return orig_set_frame_buf(top, width, format, sync);
}

int module_start(SceSize args, void *argp)
{
	unsigned int addr = sctrlHENFindFunction("sceDisplay_Service", "sceDisplay", 0x289D82FE);
	Kprintf("vshtest: started (args %d '%s'), HEN version %08x, sceDisplaySetFrameBuf at %08x\n",
		args, args ? (const char *)argp : "", sctrlHENGetVersion(), addr);
	if (addr == 0) return 0;
	orig_set_frame_buf = (void *)addr;
	sctrlHENPatchSyscall((void *)addr, hook_set_frame_buf);
	return 0;
}

int module_stop(SceSize args, void *argp)
{
	return 0;
}
