/*
 * PXP2 CFW - user-mode ISO9660 reader header
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 */
#ifndef PXP2_ISO9660_H
#define PXP2_ISO9660_H

/* Read a file from inside an .iso into buf (up to maxlen). Returns bytes
 * read, or <0. file_path is absolute inside the disc, e.g.
 * "/PSP_GAME/PARAM.SFO" or "/PSP_GAME/ICON0.PNG". */
int iso9660_read_file(const char *iso_path, const char *file_path,
                      void *buf, int maxlen);

#endif
