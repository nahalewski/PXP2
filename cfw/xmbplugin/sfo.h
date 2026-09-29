/*
 * PXP2 CFW - PARAM.SFO reader header - GPL-3.0-or-later.
 */
#ifndef PXP2_SFO_H
#define PXP2_SFO_H
/* Copy the UTF-8 string value of 'key' into out (NUL-terminated). Returns
 * length or <0 if absent. */
int sfo_get_string(const void *sfo, int sfo_len, const char *key,
                   char *out, int out_len);
#endif
