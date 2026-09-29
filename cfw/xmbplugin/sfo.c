/*
 * PXP2 CFW - PARAM.SFO reader
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * Public PSF format (psdevwiki "PARAM.SFO"): header, key table, data table.
 */
#include <string.h>
#include "sfo.h"

static unsigned int le32(const unsigned char *p)
{
	return p[0] | (p[1] << 8) | (p[2] << 16) | ((unsigned int)p[3] << 24);
}
static unsigned short le16(const unsigned char *p)
{
	return (unsigned short)(p[0] | (p[1] << 8));
}

int sfo_get_string(const void *sfo, int sfo_len, const char *key,
                   char *out, int out_len)
{
	const unsigned char *p = (const unsigned char *)sfo;
	unsigned int key_off, data_off, count, i;

	if (sfo_len < 20 || le32(p) != 0x46535000u /* "\0PSF" */)
		return -1;
	key_off  = le32(p + 8);
	data_off = le32(p + 12);
	count    = le32(p + 16);

	for (i = 0; i < count; i++) {
		const unsigned char *e = p + 20 + i * 16;
		unsigned short koff = le16(e + 0);
		unsigned int   dlen = le32(e + 4);
		unsigned int   doff = le32(e + 12);
		const char *k = (const char *)(p + key_off + koff);
		if ((int)(key_off + koff) >= sfo_len)
			return -1;
		if (strcmp(k, key) == 0) {
			unsigned int n = dlen < (unsigned int)out_len ? dlen : (unsigned int)(out_len - 1);
			if ((int)(data_off + doff + n) > sfo_len)
				return -1;
			memcpy(out, p + data_off + doff, n);
			out[n] = 0;
			return (int)n;
		}
	}
	return -1;
}
