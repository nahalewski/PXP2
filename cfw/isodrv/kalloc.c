/*
 * PXP2 CFW - minimal kernel heap (malloc/free/calloc)
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * The kernel libc used for these PRXs provides no malloc. We back the C
 * allocator with kernel partition memory blocks so both our code and the
 * bundled zlib (CSO inflate) can allocate. Each block stores its SceUID in
 * an 8-byte header preceding the returned pointer.
 *
 * ORIGINAL WORK. sceKernelAllocPartitionMemory is the public PSPSDK memory
 * API (<pspsysmem_kernel.h>).
 */
#include <string.h>
#include <pspsysmem_kernel.h>

#define KHEAP_PARTITION 1        /* kernel partition */
#define KHEAP_TYPE      PSP_SMEM_Low

void *malloc(unsigned int size)
{
	SceUID blk;
	void *base;
	unsigned int *hdr;

	if (size == 0)
		size = 1;
	/* 8-byte header for the uid, keep 8-byte alignment. */
	blk = sceKernelAllocPartitionMemory(KHEAP_PARTITION, "pxp2_heap",
	                                    KHEAP_TYPE, size + 8, NULL);
	if (blk < 0)
		return NULL;
	base = sceKernelGetBlockHeadAddr(blk);
	if (!base) {
		sceKernelFreePartitionMemory(blk);
		return NULL;
	}
	hdr = (unsigned int *)base;
	hdr[0] = (unsigned int)blk;
	hdr[1] = size;
	return (void *)(hdr + 2);
}

void free(void *ptr)
{
	unsigned int *hdr;
	if (!ptr)
		return;
	hdr = ((unsigned int *)ptr) - 2;
	sceKernelFreePartitionMemory((SceUID)hdr[0]);
}

void *calloc(unsigned int n, unsigned int size)
{
	unsigned int total = n * size;
	void *p = malloc(total);
	if (p)
		memset(p, 0, total);
	return p;
}

void *realloc(void *ptr, unsigned int size)
{
	unsigned int *hdr;
	unsigned int old;
	void *np;
	if (!ptr)
		return malloc(size);
	if (size == 0) { free(ptr); return NULL; }
	hdr = ((unsigned int *)ptr) - 2;
	old = hdr[1];
	np = malloc(size);
	if (!np)
		return NULL;
	memcpy(np, ptr, old < size ? old : size);
	free(ptr);
	return np;
}
