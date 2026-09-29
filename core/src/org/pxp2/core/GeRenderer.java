package org.pxp2.core;

/**
 * Original software rasteriser for the PSP GE. Implements vertex decoding, the through-mode and
 * world/view/projection pipelines with simple lighting, points/lines/triangles/strips/fans/sprites,
 * textures (all 16/32-bit, CLUT and DXT formats, swizzling, wrap/clamp, nearest sampling), texture
 * functions, alpha/depth tests, blending, clear mode, scissoring and block transfers.
 * Bezier and B-spline patches are tessellated on the CPU using the PSUB divisions.
 * Not yet implemented: fog, stencil, logic ops, dithering, mipmaps, lines.
 * <p>
 * Internal resolution ({@link #setScale}): at scale N the GE draws into high-resolution shadow buffers N× wider and
 * taller than the guest framebuffers, while VRAM keeps the 480×272 picture games read back (see "Shadow targets").
 * Texture filtering override ({@link #textureFilter}): nearest (default), bilinear, or an edge-directed 2×/4× upscale.
 */
public final class GeRenderer implements Ge.Renderer {
    private final Memory m;
    private final float[] world = new float[12], view = new float[12], proj = new float[16], tex = new float[12], bones = new float[96];
    private int worldIndex, viewIndex, projIndex, texIndex, boneIndex;
    public long triangles, sprites, patchesSkipped, pixels;
    private int[] r; // current register file

    public GeRenderer(Memory memory) {
        m = memory; vramArray = m.backing(Memory.VRAM, offset);
        world[0] = world[4] = world[8] = 1; view[0] = view[4] = view[8] = 1; proj[0] = proj[5] = proj[10] = proj[15] = 1;
        configure();
    }
    private static float f24(int data) { return Float.intBitsToFloat(data << 8); }

    @Override public void command(Ge ge, int cmd, int data, int address) {
        r = ge.registers;
        switch (cmd) {
            case 0x3a: worldIndex = data & 15; break;
            case 0x3b: if (worldIndex < 12) world[worldIndex++] = f24(data); break;
            case 0x3c: viewIndex = data & 15; break;
            case 0x3d: if (viewIndex < 12) view[viewIndex++] = f24(data); break;
            case 0x3e: projIndex = data & 15; break;
            case 0x3f: if (projIndex < 16) proj[projIndex++] = f24(data); break;
            case 0x40: texIndex = data & 15; break;
            case 0x41: if (texIndex < 12) tex[texIndex++] = f24(data); break;
            case 0x2a: boneIndex = data & 127; break;
            case 0x2b: if (boneIndex < 96) bones[boneIndex++] = f24(data); break;
            case 0xc4: loadClut(data); break;
            case 0xea: blockTransfer(data); break;
            case 0x04: primitive((data >>> 16) & 7, data & 0xffff); break;
            case 0x05: bezier(data & 0xff, (data >>> 8) & 0xff); break;
            case 0x06: spline(data & 0xff, (data >>> 8) & 0xff, (data >>> 16) & 3, (data >>> 18) & 3); break;
            default: break;
        }
    }

    // ---- vertices ------------------------------------------------------------------------------
    private static final class Vertex { float x, y, z, w = 1, u, v; float cr = 1, cg = 1, cb = 1, ca = 1; float nx, ny, nz; }
    private static int align(int offset, int size) { return (offset + size - 1) & -size; }
    private int address(int low24, int highRegister) { return ((r[highRegister] & 0x000f0000) << 8) | (low24 & 0xffffff); }

    private Vertex[] decode(int count) {
        int vtype = r[0x12] & 0xffffff;
        int tc = vtype & 3, col = (vtype >>> 2) & 7, nrm = (vtype >>> 5) & 3, pos = (vtype >>> 7) & 3, wt = (vtype >>> 9) & 3, idx = (vtype >>> 11) & 3;
        int weights = ((vtype >>> 14) & 7) + 1, morphs = ((vtype >>> 18) & 7) + 1; boolean through = (vtype & 0x800000) != 0;
        int[] sizes = {0, 1, 2, 4}, colorSizes = {0, 0, 0, 0, 2, 2, 2, 4};
        // Layout: weights, texcoord, color, normal, position; each aligned to its component size.
        int o = 0, maxAlign = 1, wOff = -1, tOff = -1, cOff = -1, nOff = -1, pOff;
        if (wt != 0) { o = align(o, sizes[wt]); wOff = o; o += sizes[wt] * weights; maxAlign = Math.max(maxAlign, sizes[wt]); }
        if (tc != 0) { o = align(o, sizes[tc]); tOff = o; o += sizes[tc] * 2; maxAlign = Math.max(maxAlign, sizes[tc]); }
        if (col != 0) { o = align(o, colorSizes[col]); cOff = o; o += colorSizes[col]; maxAlign = Math.max(maxAlign, colorSizes[col]); }
        if (nrm != 0) { o = align(o, sizes[nrm]); nOff = o; o += sizes[nrm] * 3; maxAlign = Math.max(maxAlign, sizes[nrm]); }
        o = align(o, Math.max(1, sizes[pos])); pOff = o; o += sizes[pos] * 3; maxAlign = Math.max(maxAlign, sizes[pos]);
        int stride = align(o, maxAlign), vaddr = address(r[0x01], 0x10), iaddr = address(r[0x02], 0x10);
        float[] morphWeights = new float[8]; for (int i = 0; i < 8; i++) morphWeights[i] = f24(r[0x2c + i]); if (morphs == 1) morphWeights[0] = 1;
        Vertex[] out = new Vertex[count];
        for (int i = 0; i < count; i++) {
            int index = i;
            if (idx == 1) index = m.read8(iaddr + i); else if (idx == 2) index = m.read16(iaddr + i * 2); else if (idx == 3) index = m.read32(iaddr + i * 4);
            Vertex v = new Vertex(); v.cr = v.cg = v.cb = v.ca = 0; float px = 0, py = 0, pz = 0, nx = 0, ny = 0, nz = 0;
            boolean hasColor = col != 0;
            for (int k = 0; k < morphs; k++) {
                int base = vaddr + (index * morphs + k) * stride; float mw = morphWeights[k];
                if (tc != 0) {
                    float u, t;
                    if (tc == 1) { u = m.read8(base + tOff); t = m.read8(base + tOff + 1); if (!through) { u /= 128f; t /= 128f; } }
                    else if (tc == 2) { u = m.read16(base + tOff); t = m.read16(base + tOff + 2); if (!through) { u /= 32768f; t /= 32768f; } }
                    else { u = m.readFloat(base + tOff); t = m.readFloat(base + tOff + 4); }
                    v.u += u * mw; v.v += t * mw;
                }
                if (hasColor) {
                    float[] c = color(base + cOff, col); v.cr += c[0] * mw; v.cg += c[1] * mw; v.cb += c[2] * mw; v.ca += c[3] * mw;
                }
                if (nrm != 0) {
                    for (int a = 0; a < 3; a++) {
                        float n = nrm == 1 ? (byte)m.read8(base + nOff + a) / 127f : nrm == 2 ? (short)m.read16(base + nOff + a * 2) / 32767f : m.readFloat(base + nOff + a * 4);
                        if (a == 0) nx += n * mw; else if (a == 1) ny += n * mw; else nz += n * mw;
                    }
                }
                for (int a = 0; a < 3; a++) {
                    float p;
                    if (pos == 1) p = through ? (byte)m.read8(base + pOff + a) : (byte)m.read8(base + pOff + a) / 127f;
                    else if (pos == 2) p = through ? (a == 2 ? m.read16(base + pOff + 4) : (short)m.read16(base + pOff + a * 2)) : (short)m.read16(base + pOff + a * 2) / 32767f;
                    else p = m.readFloat(base + pOff + a * 4);
                    if (a == 0) px += p * mw; else if (a == 1) py += p * mw; else pz += p * mw;
                }
                if (k == 0 && wt != 0 && !through) {
                    // Skinning: blend bone matrices by weight.
                    float sx = 0, sy = 0, sz = 0, snx = 0, sny = 0, snz = 0;
                    for (int b = 0; b < weights; b++) {
                        float w = wt == 1 ? m.read8(base + wOff + b) / 128f : wt == 2 ? m.read16(base + wOff + b * 2) / 32768f : m.readFloat(base + wOff + b * 4);
                        float[] q = mul43(bones, b * 12, px, py, pz, 1); sx += q[0] * w; sy += q[1] * w; sz += q[2] * w;
                        float[] n = mul43(bones, b * 12, nx, ny, nz, 0); snx += n[0] * w; sny += n[1] * w; snz += n[2] * w;
                    }
                    px = sx; py = sy; pz = sz; nx = snx; ny = sny; nz = snz;
                }
            }
            if (!hasColor) { int amc = r[0x55], ama = r[0x58]; v.cr = (amc & 255) / 255f; v.cg = ((amc >>> 8) & 255) / 255f; v.cb = ((amc >>> 16) & 255) / 255f; v.ca = (ama & 255) / 255f; }
            v.x = px; v.y = py; v.z = pz; v.nx = nx; v.ny = ny; v.nz = nz;
            out[i] = v;
        }
        // Advance the vertex/index address like the hardware does after a PRIM.
        if (idx != 0) r[0x02] = (r[0x02] & 0xff000000) | ((iaddr + count * (idx == 1 ? 1 : idx == 2 ? 2 : 4)) & 0xffffff);
        else r[0x01] = (r[0x01] & 0xff000000) | ((vaddr + count * morphs * stride) & 0xffffff);
        return out;
    }
    private float[] color(int a, int format) {
        switch (format) {
            case 4: { int p = m.read16(a); return new float[]{(p & 31) / 31f, ((p >>> 5) & 63) / 63f, ((p >>> 11) & 31) / 31f, 1}; }
            case 5: { int p = m.read16(a); return new float[]{(p & 31) / 31f, ((p >>> 5) & 31) / 31f, ((p >>> 10) & 31) / 31f, (p >>> 15) & 1}; }
            case 6: { int p = m.read16(a); return new float[]{(p & 15) / 15f, ((p >>> 4) & 15) / 15f, ((p >>> 8) & 15) / 15f, ((p >>> 12) & 15) / 15f}; }
            default: { int p = m.read32(a); return new float[]{(p & 255) / 255f, ((p >>> 8) & 255) / 255f, ((p >>> 16) & 255) / 255f, (p >>> 24) / 255f}; }
        }
    }
    private static float[] mul43(float[] mt, int o, float x, float y, float z, float w) {
        return new float[]{x * mt[o] + y * mt[o + 3] + z * mt[o + 6] + w * mt[o + 9], x * mt[o + 1] + y * mt[o + 4] + z * mt[o + 7] + w * mt[o + 10], x * mt[o + 2] + y * mt[o + 5] + z * mt[o + 8] + w * mt[o + 11]};
    }

    /**
     * True widescreen factor (display aspect / PSP aspect, e.g. 1.27 on a 20:9 phone); 1 disables it. The frame is
     * rendered anamorphically: screen-space x is compressed toward the centre by this factor, which for 3D equals a wider
     * horizontal field of view (content beyond the PSP's edges becomes visible), and the frontend stretches the frame
     * back out. Background-like batches (few vertices or patches) that already cover the full width are left as they
     * are so they fill the wide screen instead of leaving bars.
     */
    public volatile float widescreen = 1f;
    private void widen(Vertex[] vs, boolean[] ok, int count, boolean backgroundCandidate) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) if (ok[i]) { min = Math.min(min, vs[i].x); max = Math.max(max, vs[i].x); }
        if (backgroundCandidate && min <= 1f && max >= 479f) return;
        float f = 1 / widescreen, centre = 240f;
        for (int i = 0; i < count; i++) if (ok[i]) vs[i].x = centre + (vs[i].x - centre) * f;
    }

    /** Transforms to screen space; returns false if the vertex is behind the eye. */
    private boolean transform(Vertex v) {
        boolean through = (r[0x12] & 0x800000) != 0;
        if (through) return true;
        float[] w = world, vw = view, p = proj, wp = worldPos; float x = v.x, y = v.y, z = v.z; // same arithmetic as mul43, without allocation
        float wx = x * w[0] + y * w[3] + z * w[6] + w[9], wy = x * w[1] + y * w[4] + z * w[7] + w[10], wz = x * w[2] + y * w[5] + z * w[8] + w[11];
        float vx = wx * vw[0] + wy * vw[3] + wz * vw[6] + vw[9], vy = wx * vw[1] + wy * vw[4] + wz * vw[7] + vw[10], vz = wx * vw[2] + wy * vw[5] + wz * vw[8] + vw[11];
        if ((r[0x17] & 1) != 0) { wp[0] = wx; wp[1] = wy; wp[2] = wz; light(v, wp); }
        float cx = vx * p[0] + vy * p[4] + vz * p[8] + p[12];
        float cy = vx * p[1] + vy * p[5] + vz * p[9] + p[13];
        float cz = vx * p[2] + vy * p[6] + vz * p[10] + p[14];
        float cw = vx * p[3] + vy * p[7] + vz * p[11] + p[15];
        if (cw <= 1e-6f) return false;
        float sx = f24(r[0x42]), sy = f24(r[0x43]), sz = f24(r[0x44]), tx = f24(r[0x45]), ty = f24(r[0x46]), tz = f24(r[0x47]);
        v.x = cx / cw * sx + tx - (r[0x4c] & 0xffff) / 16f;
        v.y = cy / cw * sy + ty - (r[0x4d] & 0xffff) / 16f;
        v.z = cz / cw * sz + tz; v.w = cw;
        // Texture coordinates for UV mapping mode.
        int mapMode = r[0xc0] & 3;
        if (mapMode == 0) { v.u = v.u * f24(r[0x48]) + f24(r[0x4a]); v.v = v.v * f24(r[0x49]) + f24(r[0x4b]); }
        else if (mapMode == 1) { // texture matrix projection; source (0xc0 bits 8-9): model position, UV, unit normal, normal
            int src = (r[0xc0] >>> 8) & 3; float sx0, sy0, sz0;
            if (src == 0) { sx0 = x; sy0 = y; sz0 = z; }
            else if (src == 1) { sx0 = v.u; sy0 = v.v; sz0 = 0; }
            else { sx0 = v.nx; sy0 = v.ny; sz0 = v.nz; if (src == 2) { float l = (float)Math.sqrt(sx0 * sx0 + sy0 * sy0 + sz0 * sz0); if (l > 0) { sx0 /= l; sy0 /= l; sz0 /= l; } } }
            float[] t = tex; float tu = sx0 * t[0] + sy0 * t[3] + sz0 * t[6] + t[9], tv = sx0 * t[1] + sy0 * t[4] + sz0 * t[7] + t[10], tq = sx0 * t[2] + sy0 * t[5] + sz0 * t[8] + t[11];
            if (Math.abs(tq) > 1e-6f) { tu /= tq; tv /= tq; } // per-vertex projective divide (approximation of per-pixel q)
            v.u = tu; v.v = tv;
        } else if (mapMode == 2) { // shade (environment) mapping: u/v from the world normal against two lights (0xc1)
            float[] n = mul43(world, 0, v.nx, v.ny, v.nz, 0); float len = (float)Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (len > 0) { n[0] /= len; n[1] /= len; n[2] /= len; }
            v.u = (1 + shadeDot(r[0xc1] & 3, n, wx, wy, wz)) / 2; v.v = (1 + shadeDot((r[0xc1] >>> 8) & 3, n, wx, wy, wz)) / 2;
        }
        return true;
    }
    /** Shade mapping: cosine between the world normal and light {@code l}'s direction (directional) or vector to it. */
    private float shadeDot(int l, float[] n, float wx, float wy, float wz) {
        float lx = f24(r[0x63 + l * 3]), ly = f24(r[0x64 + l * 3]), lz = f24(r[0x65 + l * 3]);
        if (((r[0x5f + l] >>> 8) & 3) != 0) { lx -= wx; ly -= wy; lz -= wz; }
        float d = (float)Math.sqrt(lx * lx + ly * ly + lz * lz); if (d > 0) { lx /= d; ly /= d; lz /= d; }
        return lx * n[0] + ly * n[1] + lz * n[2];
    }
    private final float[] worldPos = new float[3];
    private void light(Vertex v, float[] worldPos) {
        float[] n = mul43(world, 0, v.nx, v.ny, v.nz, 0); float len = (float)Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (len > 0) { n[0] /= len; n[1] /= len; n[2] /= len; }
        if ((r[0x51] & 1) != 0) { n[0] = -n[0]; n[1] = -n[1]; n[2] = -n[2]; }
        int cmat = r[0x53] & 7;
        float[] vc = {v.cr, v.cg, v.cb, v.ca};
        float[] ambient = (cmat & 1) != 0 ? vc : rgb(r[0x55], r[0x58]);
        float[] diffuse = (cmat & 2) != 0 ? vc : rgb(r[0x56], 255);
        float[] emissive = rgb(r[0x54], 255), lightAmbient = rgb(r[0x5c], r[0x5d]);
        float cr = emissive[0] + lightAmbient[0] * ambient[0], cg = emissive[1] + lightAmbient[1] * ambient[1], cb = emissive[2] + lightAmbient[2] * ambient[2];
        for (int l = 0; l < 4; l++) {
            if ((r[0x18 + l] & 1) == 0) continue;
            int type = (r[0x5f + l] >>> 8) & 3;
            float lx = f24(r[0x63 + l * 3]), ly = f24(r[0x64 + l * 3]), lz = f24(r[0x65 + l * 3]);
            float dx, dy, dz;
            if (type == 0) { dx = lx; dy = ly; dz = lz; } else { dx = lx - worldPos[0]; dy = ly - worldPos[1]; dz = lz - worldPos[2]; }
            float d = (float)Math.sqrt(dx * dx + dy * dy + dz * dz); if (d > 0) { dx /= d; dy /= d; dz /= d; }
            float dot = Math.max(0, dx * n[0] + dy * n[1] + dz * n[2]);
            float att = 1;
            if (type != 0) { float a0 = f24(r[0x7b + l * 3]), a1 = f24(r[0x7c + l * 3]), a2 = f24(r[0x7d + l * 3]); float den = a0 + a1 * d + a2 * d * d; att = den > 0 ? Math.min(1, 1 / den) : 1; }
            float[] la = rgb(r[0x8f + l * 3], 255), ld = rgb(r[0x90 + l * 3], 255);
            cr += att * (la[0] * ambient[0] + ld[0] * diffuse[0] * dot); cg += att * (la[1] * ambient[1] + ld[1] * diffuse[1] * dot); cb += att * (la[2] * ambient[2] + ld[2] * diffuse[2] * dot);
        }
        v.cr = Math.min(1, cr); v.cg = Math.min(1, cg); v.cb = Math.min(1, cb); v.ca = ambient[3];
    }
    private static float[] rgb(int c, int a) { return new float[]{(c & 255) / 255f, ((c >>> 8) & 255) / 255f, ((c >>> 16) & 255) / 255f, (a & 255) / 255f}; }

    // ---- patches ----------------------------------------------------------------------------------
    private static float[] knots(int n, int type) {
        float[] k = new float[n + 5];
        for (int i = 0; i < n - 1; i++) k[i + 3] = i;
        if ((type & 1) == 0) { k[0] = -3; k[1] = -2; k[2] = -1; }
        if ((type & 2) == 0) { k[n + 2] = n - 1; k[n + 3] = n; k[n + 4] = n + 1; } else { k[n + 2] = n - 2; k[n + 3] = n - 2; k[n + 4] = n - 2; }
        return k;
    }
    private static float basis(float[] k, int i, int p, float t) {
        if (p == 0) return (t >= k[i] && t < k[i + 1]) ? 1 : 0;
        float a = k[i + p] - k[i], b = k[i + p + 1] - k[i + 1], left = 0, right = 0;
        if (a != 0) left = (t - k[i]) / a * basis(k, i, p - 1, t);
        if (b != 0) right = (k[i + p + 1] - t) / b * basis(k, i + 1, p - 1, t);
        return left + right;
    }
    /** Local support: returns the first of the (at most four) control points with non-zero cubic basis at t; weights in w[0..3]. */
    private static int splineSpan(float[] k, int n, float t, float[] w) {
        int span = 3; while (span < n - 1 && t >= k[span + 1]) span++;
        int first = Math.max(0, span - 3);
        for (int i = 0; i < 4; i++) w[i] = first + i < n ? basis(k, first + i, 3, t) : 0;
        return first;
    }
    private void accumulate(Vertex out, Vertex c, float w) {
        out.x += c.x * w; out.y += c.y * w; out.z += c.z * w; out.u += c.u * w; out.v += c.v * w;
        out.cr += c.cr * w; out.cg += c.cg * w; out.cb += c.cb * w; out.ca += c.ca * w; out.nx += c.nx * w; out.ny += c.ny * w; out.nz += c.nz * w;
    }
    // Tessellation scratch: pooled grid vertices (row-major) and their visibility, reused across patches.
    private Vertex[] grid = new Vertex[0]; private boolean[] gridOk = new boolean[0];
    /** Returns a zeroed pooled grid vertex (colour 0 for accumulation). */
    private Vertex gridVertex(int i) {
        if (i >= grid.length) { int n = Math.max(i + 1, grid.length * 2); grid = java.util.Arrays.copyOf(grid, n); gridOk = new boolean[n]; }
        Vertex v = grid[i]; if (v == null) v = grid[i] = new Vertex();
        v.x = v.y = v.z = v.u = v.v = v.nx = v.ny = v.nz = 0; v.w = 1; v.cr = v.cg = v.cb = v.ca = 0; return v;
    }
    private void emitGrid(int rows, int cols) {
        boolean hasTc = (r[0x12] & 3) != 0; Vertex[] g = grid; boolean[] ok = gridOk;
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
            Vertex v = g[y * cols + x];
            if (!hasTc) { v.u = x / (float)(cols - 1); v.v = y / (float)(rows - 1); }
            ok[y * cols + x] = transform(v);
        }
        if (widescreen > 1.001f) widen(g, ok, rows * cols, true);
        boolean cull = (r[0x1d] & 1) != 0; r[0x1d] &= ~1; // patches use PFACE rather than normal culling
        setupState();
        if (target && st.scale != 1) scaleVertices(g, rows * cols, st.scale);
        if (target) for (int y = 0; y + 1 < rows; y++) for (int x = 0; x + 1 < cols; x++) {
            int i = y * cols + x, k = i + cols; Vertex a = g[i], b = g[i + 1], c = g[k], d = g[k + 1];
            if (ok[i] && ok[k] && ok[i + 1]) triangle(a, c, b);
            if (ok[i + 1] && ok[k] && ok[k + 1]) triangle(b, c, d);
        }
        if (cull) r[0x1d] |= 1;
    }
    /** Cubic B-spline basis weights per tessellated grid line for one (control points, knot type, divisions) combination. */
    private static final class SplineBasis { int[] first; float[][] weights; }
    private final java.util.HashMap<Integer, SplineBasis> splineBases = new java.util.HashMap<>();
    private SplineBasis splineBasis(int count, int type, int div) {
        Integer key = count | type << 8 | div << 10; SplineBasis b = splineBases.get(key); if (b != null) return b;
        int p = count - 3, n = p * div + 1; float[] k = knots(count, type);
        b = new SplineBasis(); b.first = new int[n]; b.weights = new float[n][4];
        for (int g = 0; g < n; g++) b.first[g] = splineSpan(k, count, Math.min(p - 1e-4f, g / (float)div), b.weights[g]);
        splineBases.put(key, b); return b;
    }
    private int divisions(int shift) { return Math.max(1, Math.min(64, (r[0x36] >>> shift) & 0xff)); }
    private void bezier(int ucount, int vcount) {
        if (ucount < 4 || vcount < 4) return;
        Vertex[] cp = decode(ucount * vcount);
        int du = divisions(0), dv = divisions(8), pu = (ucount - 1) / 3, pv = (vcount - 1) / 3;
        int rows = pv * dv + 1, cols = pu * du + 1;
        for (int gy = 0; gy < rows; gy++) for (int gx = 0; gx < cols; gx++) {
            int patchU = Math.min(pu - 1, gx / du), patchV = Math.min(pv - 1, gy / dv);
            float s = (gx - patchU * du) / (float)du, t = (gy - patchV * dv) / (float)dv, is = 1 - s, it = 1 - t;
            float[] bu = {is * is * is, 3 * s * is * is, 3 * s * s * is, s * s * s}, bv = {it * it * it, 3 * t * it * it, 3 * t * t * it, t * t * t};
            Vertex v = gridVertex(gy * cols + gx);
            for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++) accumulate(v, cp[(patchV * 3 + j) * ucount + patchU * 3 + i], bu[i] * bv[j]);
        }
        emitGrid(rows, cols); patches++;
    }
    private void spline(int ucount, int vcount, int utype, int vtype) {
        if (ucount < 4 || vcount < 4) return;
        Vertex[] cp = decode(ucount * vcount);
        int du = divisions(0), dv = divisions(8), pu = ucount - 3, pv = vcount - 3;
        int cols = pu * du + 1, rows = pv * dv + 1;
        SplineBasis bu = splineBasis(ucount, utype, du), bv = splineBasis(vcount, vtype, dv);
        for (int gy = 0; gy < rows; gy++) {
            int fv = bv.first[gy]; float[] wv = bv.weights[gy];
            for (int gx = 0; gx < cols; gx++) {
                Vertex v = gridVertex(gy * cols + gx); int fu = bu.first[gx]; float[] wu = bu.weights[gx];
                for (int j = 0; j < 4; j++) { float b = wv[j]; if (b == 0 || fv + j >= vcount) continue;
                    for (int i = 0; i < 4; i++) { float a = wu[i]; if (a != 0 && fu + i < ucount) accumulate(v, cp[(fv + j) * ucount + fu + i], a * b); } }
            }
        }
        emitGrid(rows, cols); patches++;
    }
    public long patches;

    // ---- primitives (recorded, rasterised in parallel bands on flush) ------------------------------
    private void primitive(int type, int count) {
        if (count == 0) return;
        Vertex[] vs = decode(count);
        boolean[] ok = new boolean[count]; for (int i = 0; i < count; i++) ok[i] = transform(vs[i]);
        if (widescreen > 1.001f && (r[0xd3] & 1) == 0) widen(vs, ok, count, count <= 6);
        setupState(); if (!target) return;
        int k = st.scale; if (k != 1) scaleVertices(vs, count, k); // internal resolution: screen space is k× larger
        switch (type) {
            case 3: for (int i = 0; i + 2 < count; i += 3) if (ok[i] && ok[i + 1] && ok[i + 2]) triangle(vs[i], vs[i + 1], vs[i + 2]); break;
            case 4: for (int i = 0; i + 2 < count; i++) if (ok[i] && ok[i + 1] && ok[i + 2]) { if ((i & 1) == 0) triangle(vs[i], vs[i + 1], vs[i + 2]); else triangle(vs[i + 1], vs[i], vs[i + 2]); } break;
            case 5: for (int i = 1; i + 1 < count; i++) if (ok[0] && ok[i] && ok[i + 1]) triangle(vs[0], vs[i], vs[i + 1]); break;
            case 6: for (int i = 0; i + 1 < count; i += 2) if (ok[i] && ok[i + 1]) sprite(vs[i], vs[i + 1]); break;
            case 0: for (int i = 0; i < count; i++) if (ok[i]) { Vertex a = vs[i], b = new Vertex(); copy(a, b); b.x += k; b.y += k; sprite(a, b); } break; // a point covers k×k pixels
            default: break; // lines are not rasterised yet
        }
    }
    private static void scaleVertices(Vertex[] vs, int count, int k) { for (int i = 0; i < count; i++) { Vertex v = vs[i]; v.x *= k; v.y *= k; } }
    private static void copy(Vertex a, Vertex b) { b.x = a.x; b.y = a.y; b.z = a.z; b.w = a.w; b.u = a.u; b.v = a.v; b.cr = a.cr; b.cg = a.cg; b.cb = a.cb; b.ca = a.ca; }
    private static void put(float[] j, int at, Vertex v) {
        j[at] = v.x; j[at + 1] = v.y; j[at + 2] = v.z; j[at + 3] = v.w; j[at + 4] = v.u; j[at + 5] = v.v;
        j[at + 6] = v.cr; j[at + 7] = v.cg; j[at + 8] = v.cb; j[at + 9] = v.ca;
    }
    private void sprite(Vertex a, Vertex b) {
        sprites++; float[] j = new float[21]; j[0] = 1; put(j, 1, a); put(j, 11, b); record(j);
    }
    private void triangle(Vertex a, Vertex b, Vertex c) {
        float area = (b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y);
        if (area == 0 || Float.isNaN(area)) return;
        if ((r[0x1d] & 1) != 0 && !st.clear) { boolean ccw = (r[0x9b] & 1) != 0; if ((area > 0) != ccw) return; } // area > 0 is clockwise on screen (y down); CULL 1 keeps CCW faces
        triangles++; float[] j = new float[31]; put(j, 1, a); put(j, 11, b); put(j, 21, c); record(j);
    }

    // ---- deferred parallel rasterisation ----------------------------------------------------------
    // Primitives are binned by 16-row chunks (rows 0..511, the scissor limit); workers pull chunks dynamically and
    // draw each chunk's primitives in submission order. Output does not depend on the number of workers.
    private static final int WORKERS = Integer.getInteger("pxp2.geworkers", Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(WORKERS, task -> {
        Thread t = new Thread(task, "PXP2 GE raster"); t.setDaemon(true); return t; });
    // At render scale k the chunks are 16·k rows high, so there are always 32 of them (rows 0..512·k-1).
    private static final int CHUNKS = 32, PARALLEL_MIN = 4096; // bbox pixels below which one thread draws
    private int chunk = 16;
    private final java.util.ArrayList<float[]> jobs = new java.util.ArrayList<>();
    private final java.util.ArrayList<Raster> jobStates = new java.util.ArrayList<>();
    private final int[][] bins = new int[CHUNKS][256]; private final int[] binCount = new int[CHUNKS];
    private final java.util.concurrent.atomic.AtomicInteger nextChunk = new java.util.concurrent.atomic.AtomicInteger();
    private final Span[] spans = new Span[WORKERS + 1];
    private final java.util.ArrayList<java.util.concurrent.Callable<Long>> tasks = new java.util.ArrayList<>();
    private long work; public long flushes, flushNanos;
    private Raster st; private boolean target;
    /** Sizes the chunks and per-thread spans for the current scale (a span must hold one full row: 1024·scale pixels). */
    private void configure() {
        chunk = 16 * scale; tasks.clear();
        for (int i = 0; i <= WORKERS; i++) { Span s = spans[i] = new Span(2048 * scale); if (i > 0) tasks.add(() -> rasterChunks(s)); }
    }
    private void record(float[] j) {
        boolean tri = j[0] == 0;
        float y0 = tri ? Math.min(j[2], Math.min(j[12], j[22])) : Math.min(j[2], j[12]), y1 = tri ? Math.max(j[2], Math.max(j[12], j[22])) : Math.max(j[2], j[12]);
        float x0 = tri ? Math.min(j[1], Math.min(j[11], j[21])) : Math.min(j[1], j[11]), x1 = tri ? Math.max(j[1], Math.max(j[11], j[21])) : Math.max(j[1], j[11]);
        int a = st.sy0, b = st.sy1; // conservative row range of the drawn pixels (all rows if not finite)
        if (y0 == y0 && y1 == y1) { a = (int)Math.max(a, Math.floor(y0) - 1); b = (int)Math.min(b, Math.ceil(y1) + 1); }
        if (st.cs != null && a <= b) { // high-resolution target: make room for the rows and note what the resolve must copy back
            int k = st.scale, need = b / k + 1;
            if (need > st.cs.rows || st.zs != null && need > st.zs.rows) grow(st, need);
            int cx0 = x0 == x0 ? (int)Math.max(st.sx0, Math.floor(x0) - 1) : st.sx0, cx1 = x1 == x1 ? (int)Math.min(st.sx1, Math.ceil(x1) + 1) : st.sx1;
            drawn(st.cs, cx0 / k, cx1 / k, a / k, b / k); if (st.zs != null && st.depthWrite) drawn(st.zs, cx0 / k, cx1 / k, a / k, b / k);
        } else if (st.direct && a <= b) { // a 1× draw into VRAM while shadows exist: shadows over these bytes re-read them
            int bpp = st.fbFormat == 3 ? 4 : 2; m.markVramWritten(st.gFbBase + st.gsy0 * st.gStride * bpp, (st.gsy1 - st.gsy0 + 1) * st.gStride * bpp);
            if (st.depthWrite) m.markVramWritten(st.gZbBase + st.gsy0 * st.gZbStride * 2, (st.gsy1 - st.gsy0 + 1) * st.gZbStride * 2);
        }
        int id = jobs.size(); jobs.add(j); jobStates.add(st);
        if (st != marked) { // guest bytes this state may write, for render-to-texture hazard checks (VRAM coordinates at any scale)
            Raster s = marked = st; int bpp = s.fbFormat == 3 ? 4 : 2;
            markDirty(s.gFb, s.gFbBase + s.gsy0 * s.gStride * bpp, s.gFbBase + (s.gsy1 + 1) * s.gStride * bpp);
            if (s.depthWrite) markDirty(s.gZb, s.gZbBase + s.gsy0 * s.gZbStride * 2, s.gZbBase + (s.gsy1 + 1) * s.gZbStride * 2);
            target(s.gFb, s.gFbBase, s.gStride * bpp, (s.gsx1 + 1) * bpp, s.gsy0, s.gsy1);
            if (s.gZb != null && (s.depthTest || s.depthWrite)) target(s.gZb, s.gZbBase, s.gZbStride * 2, (s.gsx1 + 1) * 2, s.gsy0, s.gsy1);
            if (s.serial) sequential = true;
        }
        if (a <= b) {
            long w = x0 == x0 && x1 == x1 ? (long)Math.min(st.sx1, Math.ceil(x1) + 1) - (long)Math.max(st.sx0, Math.floor(x0) - 1) + 1 : 512;
            work += Math.max(1, w) * (b - a + 1);
            for (int c = a / chunk, ce = b / chunk; c <= ce; c++) {
                int[] bin = bins[c]; if (binCount[c] == bin.length) bins[c] = bin = java.util.Arrays.copyOf(bin, bin.length * 2);
                bin[binCount[c]++] = id;
            }
        }
        if (jobs.size() >= 16384) flush();
    }
    /** Rasterises all recorded primitives. */
    @Override public void flush() {
        int n = jobs.size(); if (n == 0) return; long t0 = System.nanoTime(); flushes++;
        nextChunk.set(0);
        if (sequential) { // aliasing targets: draw everything in submission order, like a single band
            Span sp = spans[0];
            for (int i = 0, rows = 512 * scale; i < n; i++) { float[] j = jobs.get(i); Raster r = jobStates.get(i); pixels += j[0] == 0 ? r.triangle(j, 0, rows, sp) : r.sprite(j, 0, rows, sp); }
        } else if (WORKERS == 1 || work < PARALLEL_MIN) pixels += rasterChunks(spans[0]);
        else {
            java.util.List<java.util.concurrent.Future<Long>> parts = new java.util.ArrayList<>(WORKERS);
            for (java.util.concurrent.Callable<Long> t : tasks) parts.add(POOL.submit(t));
            pixels += rasterChunks(spans[0]); // calling thread works too
            for (java.util.concurrent.Future<Long> f : parts) {
                try { pixels += f.get(); } catch (Exception e) { throw new IllegalStateException("GE raster worker failed", e); }
            }
        }
        jobs.clear(); jobStates.clear(); java.util.Arrays.fill(binCount, 0); work = 0;
        marked = null; sequential = false; targets.clear(); dirtyArray = null; dirtyLo = Integer.MAX_VALUE; dirtyHi = Integer.MIN_VALUE; dirtyOther = false;
        if (!shadows.isEmpty()) { long r0 = System.nanoTime(); resolve(); resolveNanos += System.nanoTime() - r0; } // VRAM gets the downsampled picture before anything (CPU, display, textures) reads it
        batch++; batchMode = 0;
        flushNanos += System.nanoTime() - t0;
    }
    private long rasterChunks(Span s) {
        long count = 0;
        for (int c; (c = nextChunk.getAndIncrement()) < CHUNKS; ) {
            int[] bin = bins[c]; int y0 = c * chunk, y1 = y0 + chunk;
            for (int i = 0, n = binCount[c]; i < n; i++) {
                float[] j = jobs.get(bin[i]); Raster r = jobStates.get(bin[i]);
                count += j[0] == 0 ? r.triangle(j, y0, y1, s) : r.sprite(j, y0, y1, s);
            }
        }
        return count;
    }
    // Row chunks may be drawn in any order, which is only equivalent to submission order if every buffer byte belongs to
    // one screen row: buffers of the pending states must not overlap unless they have the same row mapping.
    private static final class Target { byte[] array; int base, pitch; long lo, hi; }
    private final java.util.ArrayList<Target> targets = new java.util.ArrayList<>(); private boolean sequential;
    private void target(byte[] array, int base, int pitch, int rowBytes, int y0, int y1) {
        if (pitch < rowBytes) sequential = true; // rows overlap each other
        long lo = base + (long)y0 * pitch, hi = base + (long)(y1 + 1) * pitch;
        for (Target t : targets) {
            if (t.array != array) continue;
            if (t.base == base && t.pitch == pitch) { t.lo = Math.min(t.lo, lo); t.hi = Math.max(t.hi, hi); return; }
            if (lo < t.hi && t.lo < hi) sequential = true;
        }
        Target t = new Target(); t.array = array; t.base = base; t.pitch = pitch; t.lo = lo; t.hi = hi; targets.add(t);
    }
    // Byte range of guest memory that pending primitives may write (one backing array; any other array → dirtyOther).
    private Raster marked; private byte[] dirtyArray; private int dirtyLo = Integer.MAX_VALUE, dirtyHi = Integer.MIN_VALUE; private boolean dirtyOther;
    private void markDirty(byte[] a, int lo, int hi) {
        if (dirtyArray != null && dirtyArray != a) { dirtyOther = true; return; }
        dirtyArray = a; dirtyLo = Math.min(dirtyLo, lo); dirtyHi = Math.max(dirtyHi, hi);
    }
    /** True if pending drawing may have written the current texture's bytes (render-to-texture). */
    private boolean textureDirty() {
        int tbw = r[0xa8] & 0xffff, base = ((r[0xa8] & 0x0f0000) << 8) | (r[0xa0] & 0xffffff);
        int w = 1 << (r[0xb8] & 15), h = 1 << ((r[0xb8] >>> 8) & 15), format = r[0xc3] & 15;
        int bits = format == 4 || format == 8 ? 4 : format == 5 || format >= 9 ? 8 : format == 3 || format == 7 ? 32 : 16;
        return dirty(base, (long)Math.max(16, Math.max(tbw, w) * bits / 8) * ((h + 7) & ~7) + 16);
    }
    /** True if pending drawing may have written guest bytes [address, address + len). */
    private boolean dirty(int address, long len) {
        if (jobs.isEmpty()) return false; if (dirtyOther) return true;
        if (!m.valid(address, 1)) return false; // nothing readable there
        byte[] a = m.backing(address, offset); int lo = offset[0];
        if (a != dirtyArray) return false;
        return lo + len > a.length || (lo < dirtyHi && lo + len > dirtyLo);
    }

    // ---- per-primitive state snapshot and per-pixel pipeline ---------------------------------------
    private void setupState() {
        boolean tex = (r[0xd3] & 1) == 0 && (r[0x1e] & 1) != 0;
        // Render-to-texture: flush pending drawing before reading a texture it may have written.
        if (tex && textureDirty()) flush();
        Raster s = new Raster(); st = s;
        int fbAddr = 0x04000000 | (r[0x9c] & 0xffffff), zAddr = 0x04000000 | (r[0x9e] & 0xffffff);
        s.fbStride = r[0x9d] & 0x7fc; s.fbFormat = r[0xd2] & 3; s.zbStride = r[0x9f] & 0x7fc;
        s.sx0 = r[0xd4] & 0x3ff; s.sy0 = (r[0xd4] >>> 10) & 0x3ff; s.sx1 = Math.min(r[0xd5] & 0x3ff, s.fbStride - 1); s.sy1 = Math.min((r[0xd5] >>> 10) & 0x3ff, 511);
        target = s.fbStride != 0 && m.valid(fbAddr, 4);
        if (!target) return;
        s.fb = m.backing(fbAddr, offset); s.fbBase = offset[0];
        s.zb = s.zbStride != 0 && m.valid(zAddr, 2) ? m.backing(zAddr, offset) : null; s.zbBase = offset[0];
        s.through = (r[0x12] & 0x800000) != 0; s.flat = (r[0x50] & 1) == 0;
        s.clear = (r[0xd3] & 1) != 0; s.clearMode = r[0xd3];
        s.depthTest = !s.clear && (r[0x23] & 1) != 0 && s.zb != null; s.depthFunc = r[0xde] & 7;
        s.depthWrite = s.zb != null && (s.clear ? (s.clearMode & 0x400) != 0 : s.depthTest && (r[0xe7] & 1) == 0);
        s.alphaTest = !s.clear && (r[0x22] & 1) != 0; s.alphaFunc = r[0xdb] & 7; s.alphaMask = (r[0xdb] >>> 16) & 255; s.alphaRef = ((r[0xdb] >>> 8) & 255) & s.alphaMask;
        s.blend = !s.clear && (r[0x21] & 1) != 0; s.blendSrc = r[0xdf] & 15; s.blendDst = (r[0xdf] >>> 4) & 15; s.blendOp = (r[0xdf] >>> 8) & 15; s.fixA = r[0xe0] & 0xffffff; s.fixB = r[0xe1] & 0xffffff;
        s.texture = tex;
        // Pixels are shaded in batches (depth test, colour, depth write as separate passes); if one pixel's depth could
        // alias another pixel's depth or colour bytes, use the strictly per-pixel pipeline instead.
        if (s.zb != null && (s.depthTest || s.depthWrite)) {
            int bpp = s.fbFormat == 3 ? 4 : 2;
            long zLo = s.zbBase + (long)s.sy0 * s.zbStride * 2, zHi = s.zbBase + (long)(s.sy1 + 1) * s.zbStride * 2;
            long fLo = s.fbBase + (long)s.sy0 * s.fbStride * bpp, fHi = s.fbBase + (long)(s.sy1 + 1) * s.fbStride * bpp;
            s.aliased = s.zb == s.fb && zLo < fHi && fLo < zHi;
            s.serial = s.depthTest && s.depthWrite && s.zbStride <= s.sx1 || s.aliased;
        }
        s.gFb = s.fb; s.gFbBase = s.fbBase; s.gStride = s.fbStride; s.gZb = s.zb; s.gZbBase = s.zbBase; s.gZbStride = s.zbStride;
        s.gsx1 = s.sx1; s.gsy0 = s.sy0; s.gsy1 = s.sy1;
        if (scale > 1) highResolution(s);
        if (tex) {
            s.texFunc = r[0xc9] & 7; s.texAlpha = (r[0xc9] & 0x100) != 0; s.texDouble = (r[0xc9] & 0x10000) != 0;
            s.envR = r[0xca] & 255; s.envG = (r[0xca] >>> 8) & 255; s.envB = (r[0xca] >>> 16) & 255;
            s.clampU = (r[0xc7] & 1) != 0; s.clampV = (r[0xc7] & 0x100) != 0;
            s.texels = texture(); s.texW = 1 << (r[0xb8] & 15); s.texH = 1 << ((r[0xb8] >>> 8) & 15);
            if (scale > 1 || textureFilter != 0) textureOptions(s);
        }
    }
    private final int[] offset = new int[1];

    /**
     * Per-thread pixel batch: covered pixels (one or more rows of one primitive, in increasing address order) with their
     * framebuffer/depth byte offsets and interpolated attributes.
     */
    static final class Span {
        final int cap; // at least one full row (1024 pixels per unit of scale)
        final int[] p, zi, z, t, r, g, b, a; final float[] u, v;
        int job; // frame generation: MotionVectors job id of the primitive being rasterised
        Span(int cap) {
            this.cap = cap; p = new int[cap]; zi = new int[cap]; z = new int[cap]; t = new int[cap]; r = new int[cap]; g = new int[cap]; b = new int[cap]; a = new int[cap];
            u = new float[cap]; v = new float[cap];
        }
        void move(int from, int to) { p[to] = p[from]; zi[to] = zi[from]; z[to] = z[from]; r[to] = r[from]; g[to] = g[from]; b[to] = b[from]; a[to] = a[from]; u[to] = u[from]; v[to] = v[from]; }
    }

    /** Immutable-after-setup render state of one primitive batch, with the rasteriser that uses it. */
    static final class Raster {
        boolean clear, texture, depthTest, depthWrite, alphaTest, blend, texAlpha, texDouble, through, flat, clampU, clampV;
        byte[] fb, zb; int fbBase, fbStride, fbFormat, zbBase, zbStride, sx0, sy0, sx1, sy1;
        int clearMode, depthFunc, alphaFunc, alphaRef, alphaMask, texFunc, envR, envG, envB, blendSrc, blendDst, blendOp, fixA, fixB;
        int[] texels; int texW, texH; boolean serial, aliased;
        // Internal resolution: scale of this state's screen space; the shadows it draws into (null when drawing VRAM
        // directly); the guest (1×, VRAM) framebuffer geometry used for hazard tracking.
        int scale = 1; Shadow cs, zs; boolean direct;
        byte[] gFb, gZb; int gFbBase, gStride, gZbBase, gZbStride, gsx1, gsy0, gsy1;
        // General texture sampler (scaled, filtered or high-resolution framebuffer texels): gtex is gW×gH texels, through-mode
        // coordinates are multiplied by gF. When th != null the texels inside thCols×thRows come from a shadow buffer.
        boolean generic, bilinear; int[] gtex; int gW, gH; float gF = 1;
        byte[] th; int thBase, thPitch, thBpp, thFormat, thCols, thRows, thS;

        long sprite(float[] j, int bandY0, int bandY1, Span s) {
            float ax = j[1], ay = j[2], au = j[5], av = j[6], bx = j[11], by = j[12], bz = j[13], bu = j[15], bv = j[16];
            float x0 = Math.min(ax, bx), x1 = Math.max(ax, bx), y0 = Math.min(ay, by), y1 = Math.max(ay, by);
            boolean flipU = ax > bx, flipV = ay > by;
            int ix0 = Math.max((int)Math.ceil(x0 - 0.5f), sx0), ix1 = Math.min((int)Math.ceil(x1 - 0.5f), sx1 + 1);
            int iy0 = Math.max(Math.max((int)Math.ceil(y0 - 0.5f), sy0), bandY0), iy1 = Math.min(Math.min((int)Math.ceil(y1 - 0.5f), sy1 + 1), bandY1);
            float w = x1 - x0, h = y1 - y0; if (w <= 0 || h <= 0 || ix0 >= ix1) return 0;
            int cr = clamp255(j[17]), cg = clamp255(j[18]), cb = clamp255(j[19]), ca = clamp255(j[20]), z = zOf(bz), n = ix1 - ix0;
            float du = (bu - au) / w * (flipU ? -1 : 1); long count = 0;
            if (clear && fbFormat == 3 && !serial) { // plain fill
                boolean rgb = (clearMode & 0x100) != 0, alpha = (clearMode & 0x200) != 0, zw = depthWrite; byte[] f = fb, zbuf = zb;
                for (int y = iy0; y < iy1; y++) {
                    int row = fbBase + y * fbStride * 4, zrow = zbBase + y * zbStride * 2;
                    if (row + ix0 * 4 < 0 || row + (ix1 - 1) * 4 + 4 > f.length) continue;
                    for (int pi = row + ix0 * 4, pe = row + ix1 * 4; pi < pe; pi += 4) { if (rgb) { f[pi] = (byte)cr; f[pi + 1] = (byte)cg; f[pi + 2] = (byte)cb; } if (alpha) f[pi + 3] = (byte)ca; }
                    if (zw) for (int x = ix0; x < ix1; x++) { int zi = zrow + x * 2; if (zi >= 0 && zi + 2 <= zbuf.length) { zbuf[zi] = (byte)z; zbuf[zi + 1] = (byte)(z >>> 8); } }
                    count += n;
                }
                if (count == (long)n * (iy1 - iy0 > 0 ? iy1 - iy0 : 0)) return count;
                count = 0; // some rows cross the framebuffer end: fall back to the per-pixel path
            }
            int bpp = fbFormat == 3 ? 4 : 2; boolean needZ = depthTest || depthWrite;
            for (int y = iy0; y < iy1; y++) { // the stages modify the buffers, so refill them per row
                java.util.Arrays.fill(s.z, 0, n, z); java.util.Arrays.fill(s.r, 0, n, cr); java.util.Arrays.fill(s.g, 0, n, cg); java.util.Arrays.fill(s.b, 0, n, cb); java.util.Arrays.fill(s.a, 0, n, ca);
                float fy = (y + 0.5f - y0) / h; if (flipV) fy = 1 - fy; float v = av + (bv - av) * fy;
                float fx = (ix0 + 0.5f - x0) / w; if (flipU) fx = 1 - fx; float u = au + (bu - au) * fx;
                int[] P = s.p, ZI = s.zi; float[] U = s.u, V = s.v; int row = fbBase + y * fbStride * bpp, zrow = zbBase + y * zbStride * 2;
                for (int i = 0, x = ix0; x < ix1; x++, i++, u += du) { P[i] = row + x * bpp; if (needZ) ZI[i] = zrow + x * 2; U[i] = u; V[i] = v; }
                count += shade(s, n);
            }
            return count;
        }
        /** Span-based edge-function rasteriser with perspective-correct texture coordinates. */
        long triangle(float[] j, int bandY0, int bandY1, Span s) {
            float ax = j[1], ay = j[2], az = j[3], bx = j[11], by = j[12], bz = j[13], cx = j[21], cy = j[22], cz = j[23];
            float area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
            int minX = (int)Math.max(sx0, Math.floor(Math.min(ax, Math.min(bx, cx)))), maxX = (int)Math.min(sx1, Math.ceil(Math.max(ax, Math.max(bx, cx))));
            // Edge values are stepped from the triangle's own first row, so results do not depend on the band split.
            int minY = (int)Math.max(sy0, Math.floor(Math.min(ay, Math.min(by, cy)))), maxY = (int)Math.min(sy1, Math.ceil(Math.max(ay, Math.max(by, cy))));
            if (minX > maxX || minY > maxY || maxX - minX > 4096 * scale || maxY - minY > 4096 * scale) return 0;
            int yEnd = Math.min(maxY, bandY1 - 1); if (yEnd < bandY0) return 0;
            float inv = 1 / area;
            float iwa = through ? 1 : 1 / j[4], iwb = through ? 1 : 1 / j[14], iwc = through ? 1 : 1 / j[24];
            float e0dx = (by - cy) * inv, e0dy = (cx - bx) * inv, e1dx = (cy - ay) * inv, e1dy = (ax - cx) * inv, e2dx = -e0dx - e1dx;
            float px0 = minX + 0.5f, py0 = minY + 0.5f;
            float e0row = ((bx - px0) * (cy - py0) - (cx - px0) * (by - py0)) * inv, e1row = ((cx - px0) * (ay - py0) - (ax - px0) * (cy - py0)) * inv;
            int y = minY; for (; y < bandY0; y++) { e0row += e0dy; e1row += e1dy; }
            int fr = clamp255(j[27]), fg = clamp255(j[28]), fb2 = clamp255(j[29]), fa = clamp255(j[30]);
            float u0 = j[5], u1 = j[15], u2 = j[25], v0 = j[6], v1 = j[16], v2 = j[26];
            float r0 = j[7], r1 = j[17], r2 = j[27], g0 = j[8], g1 = j[18], g2 = j[28], b0 = j[9], b1 = j[19], b2 = j[29], a0 = j[10], a1 = j[20], a2 = j[30];
            boolean gouraud = !flat;
            // Gouraud colours that round to the same 0..255 value everywhere on the triangle are drawn as flat. Interpolation
            // weights are convex up to the -1e-5 edge tolerance, so with a bounded 1/w ratio every interpolated colour lies
            // within m of [min, max] and clamp255 (monotonic) is provably constant when it agrees at both ends.
            if (gouraud && (through || Math.max(iwa, Math.max(iwb, iwc)) <= 1000 * Math.min(iwa, Math.min(iwb, iwc)))) {
                int cr = flatColor(r0, r1, r2), cg = flatColor(g0, g1, g2), cb = flatColor(b0, b1, b2), ca = flatColor(a0, a1, a2);
                if ((cr | cg | cb | ca) >= 0) { gouraud = false; fr = cr; fg = cg; fb2 = cb; fa = ca; }
            }
            boolean perspective = texture || gouraud, tex = texture, needZ = depthTest || depthWrite; long count = 0;
            // A triangle with one depth everywhere must write and test exactly that depth: the weights only sum to 1 within
            // float rounding, and az*w0 + bz*w1 + cz*w2 truncates e.g. 65535 to 65534 at scattered pixels, which then fail
            // GEQUAL against an equal-depth layer below (DBZ's full-screen fade quads left speckled holes).
            boolean flatZ = az == bz && bz == cz; int zFlat = zOf(az);
            int[] P = s.p, ZI = s.zi, Z = s.z, R = s.r, G = s.g, B = s.b, A = s.a; float[] U = s.u, V = s.v; int n = 0, bpp = fbFormat == 3 ? 4 : 2;
            for (; y <= yEnd; y++, e0row += e0dy, e1row += e1dy) {
                float lo = 0, hi = maxX - minX, e2row = 1 - e0row - e1row;
                if (e0dx > 0) lo = Math.max(lo, -e0row / e0dx); else if (e0dx < 0) hi = Math.min(hi, e0row / -e0dx); else if (e0row < 0) continue;
                if (e1dx > 0) lo = Math.max(lo, -e1row / e1dx); else if (e1dx < 0) hi = Math.min(hi, e1row / -e1dx); else if (e1row < 0) continue;
                if (e2dx > 0) lo = Math.max(lo, -e2row / e2dx); else if (e2dx < 0) hi = Math.min(hi, e2row / -e2dx); else if (e2row < 0) continue;
                if (lo > hi) continue;
                int xs = minX - floor(1e-3f - lo), xe = minX + floor(hi + 1e-3f); // ceil(lo - 1e-3), floor(hi + 1e-3)
                if (n + xe - xs + 1 > s.cap) { count += flushSpan(s, n, gouraud, fr, fg, fb2, fa); n = 0; } // rows are batched
                float w0 = e0row + e0dx * (xs - minX), w1 = e1row + e1dx * (xs - minX); int row = fbBase + y * fbStride * bpp, zrow = zbBase + y * zbStride * 2;
                for (int x = xs; x <= xe; x++, w0 += e0dx, w1 += e1dx) {
                    float w2 = 1 - w0 - w1;
                    if (w0 < -1e-5f || w1 < -1e-5f || w2 < -1e-5f) continue;
                    P[n] = row + x * bpp; if (needZ) { ZI[n] = zrow + x * 2; Z[n] = flatZ ? zFlat : zOf(az * w0 + bz * w1 + cz * w2); }
                    if (perspective) {
                        float p0 = w0 * iwa, p1 = w1 * iwb, p2 = w2 * iwc, ps = 1 / (p0 + p1 + p2); p0 *= ps; p1 *= ps; p2 *= ps;
                        if (tex) { U[n] = u0 * p0 + u1 * p1 + u2 * p2; V[n] = v0 * p0 + v1 * p1 + v2 * p2; }
                        if (gouraud) { R[n] = clamp255(r0 * p0 + r1 * p1 + r2 * p2); G[n] = clamp255(g0 * p0 + g1 * p1 + g2 * p2); B[n] = clamp255(b0 * p0 + b1 * p1 + b2 * p2); A[n] = clamp255(a0 * p0 + a1 * p1 + a2 * p2); }
                    }
                    n++;
                }
            }
            return count + flushSpan(s, n, gouraud, fr, fg, fb2, fa);
        }
        private int flushSpan(Span s, int n, boolean gouraud, int fr, int fg, int fb, int fa) {
            if (n == 0) return 0;
            if (!gouraud) { java.util.Arrays.fill(s.r, 0, n, fr); java.util.Arrays.fill(s.g, 0, n, fg); java.util.Arrays.fill(s.b, 0, n, fb); java.util.Arrays.fill(s.a, 0, n, fa); }
            return shade(s, n);
        }
        private int readPixel(int i) {
            byte[] b = fb;
            switch (fbFormat) {
                case 3: return (b[i] & 255) | ((b[i + 1] & 255) << 8) | ((b[i + 2] & 255) << 16) | (b[i + 3] << 24);
                case 0: { int p = (b[i] & 255) | ((b[i + 1] & 255) << 8); return 0xff000000 | (((p >>> 11) & 31) * 255 / 31 << 16) | (((p >>> 5) & 63) * 255 / 63 << 8) | ((p & 31) * 255 / 31); }
                case 1: { int p = (b[i] & 255) | ((b[i + 1] & 255) << 8); return ((p >>> 15) * 255 << 24) | (((p >>> 10) & 31) * 255 / 31 << 16) | (((p >>> 5) & 31) * 255 / 31 << 8) | ((p & 31) * 255 / 31); }
                default: { int p = (b[i] & 255) | ((b[i + 1] & 255) << 8); return (((p >>> 12) & 15) * 17 << 24) | (((p >>> 8) & 15) * 17 << 16) | (((p >>> 4) & 15) * 17 << 8) | ((p & 15) * 17); }
            }
        }
        private void writePixel(int i, int c) {
            byte[] b = fb; int rr = c & 255, gg = (c >>> 8) & 255, bb = (c >>> 16) & 255, aa = c >>> 24, p;
            switch (fbFormat) {
                case 3: b[i] = (byte)c; b[i + 1] = (byte)(c >>> 8); b[i + 2] = (byte)(c >>> 16); b[i + 3] = (byte)(c >>> 24); return;
                case 0: p = (rr >>> 3) | ((gg >>> 2) << 5) | ((bb >>> 3) << 11); break;
                case 1: p = (rr >>> 3) | ((gg >>> 3) << 5) | ((bb >>> 3) << 10) | ((aa >>> 7) << 15); break;
                default: p = (rr >>> 4) | ((gg >>> 4) << 4) | ((bb >>> 4) << 8) | ((aa >>> 4) << 12); break;
            }
            b[i] = (byte)p; b[i + 1] = (byte)(p >>> 8);
        }
        /**
         * Runs the per-pixel pipeline over the n covered pixels of row y in {@code s} as a series of stages, each a
         * tight loop (state branches are per span, not per pixel). Colours are 0..255; texture coordinates are texels in
         * through mode, normalised otherwise. Returns pixels written.
         */
        private int shade(Span s, int n) {
            final int[] P = s.p, ZI = s.zi, Z = s.z, R = s.r, G = s.g, B = s.b, A = s.a; final byte[] f = fb;
            final int bpp = fbFormat == 3 ? 4 : 2;
            if (serial) { int c = 0; for (int i = 0; i < n; i++) c += pixel(P[i], s.zi[i], Z[i], s.u[i], s.v[i], R[i], G[i], B[i], A[i]); return c; }
            if (P[0] < 0 || P[n - 1] + 4 > f.length) { // drop pixels outside the framebuffer array (offsets increase)
                int k = 0; for (int i = 0; i < n; i++) { int pi = P[i]; if (pi >= 0 && pi + 4 <= f.length) s.move(i, k++); }
                if ((n = k) == 0) return 0;
            }
            if (clear) {
                boolean rgb = (clearMode & 0x100) != 0, alpha = (clearMode & 0x200) != 0;
                for (int i = 0; i < n; i++) {
                    int pi = P[i];
                    if (bpp == 4) { if (rgb) { f[pi] = (byte)R[i]; f[pi + 1] = (byte)G[i]; f[pi + 2] = (byte)B[i]; } if (alpha) f[pi + 3] = (byte)A[i]; }
                    else { int dst = readPixel(pi); writePixel(pi, (rgb ? (B[i] << 16) | (G[i] << 8) | R[i] : dst & 0xffffff) | ((alpha ? A[i] : dst >>> 24) << 24)); }
                }
                if (depthWrite) writeDepth(s, n);
                return n;
            }
            if (depthTest) {
                byte[] zbuf = zb; int k = 0;
                for (int i = 0; i < n; i++) {
                    int zi = ZI[i];
                    if (zi >= 0 && zi + 2 <= zbuf.length && !test(depthFunc, Z[i], (zbuf[zi] & 255) | ((zbuf[zi + 1] & 255) << 8))) continue;
                    if (k != i) s.move(i, k); k++;
                }
                if ((n = k) == 0) return 0;
            }
            if (texture) sample(s, n);
            if (alphaTest) {
                int k = 0;
                for (int i = 0; i < n; i++) { if (!test(alphaFunc, A[i] & alphaMask, alphaRef)) continue; if (k != i) s.move(i, k); k++; }
                if ((n = k) == 0) return 0;
            }
            if (bpp == 4 && !blend) {
                for (int i = 0; i < n; i++) { int pi = P[i]; f[pi] = (byte)R[i]; f[pi + 1] = (byte)G[i]; f[pi + 2] = (byte)B[i]; } // alpha is kept
            } else if (bpp == 4 && blendOp == 0 && blendSrc == 2 && (blendDst == 10 || blendDst == 3)) {
                // Common case: src*alpha + dst*(fixed | 1-alpha).
                boolean fixed = blendDst == 10; int fr = fixB & 255, fg = (fixB >>> 8) & 255, fbb = (fixB >>> 16) & 255;
                for (int i = 0; i < n; i++) {
                    int pi = P[i], sa = A[i], dr, dg, db;
                    if (!fixed) dr = dg = db = 255 - sa; else { dr = fr; dg = fg; db = fbb; }
                    int vr = (R[i] * sa + (f[pi] & 255) * dr) / 255, vg = (G[i] * sa + (f[pi + 1] & 255) * dg) / 255, vb = (B[i] * sa + (f[pi + 2] & 255) * db) / 255;
                    f[pi] = (byte)(vr > 255 ? 255 : vr); f[pi + 1] = (byte)(vg > 255 ? 255 : vg); f[pi + 2] = (byte)(vb > 255 ? 255 : vb);
                }
            } else {
                for (int i = 0; i < n; i++) {
                    int pi = P[i], dst = readPixel(pi), sr = R[i], sg = G[i], sb = B[i];
                    if (blend) {
                        int sa = A[i], dr = dst & 255, dg = (dst >>> 8) & 255, db = (dst >>> 16) & 255, da = dst >>> 24;
                        int fsR, fsG, fsB, fdR, fdG, fdB;
                        switch (blendSrc) {
                            case 0: fsR = dr; fsG = dg; fsB = db; break; case 1: fsR = 255 - dr; fsG = 255 - dg; fsB = 255 - db; break;
                            case 10: fsR = fixA & 255; fsG = (fixA >>> 8) & 255; fsB = (fixA >>> 16) & 255; break;
                            default: fsR = fsG = fsB = alphaFactor(blendSrc, sa, da);
                        }
                        switch (blendDst) {
                            case 0: fdR = sr; fdG = sg; fdB = sb; break; case 1: fdR = 255 - sr; fdG = 255 - sg; fdB = 255 - sb; break;
                            case 10: fdR = fixB & 255; fdG = (fixB >>> 8) & 255; fdB = (fixB >>> 16) & 255; break;
                            default: fdR = fdG = fdB = alphaFactor(blendDst, sa, da);
                        }
                        sr = blendChannel(blendOp, sr, dr, fsR, fdR); sg = blendChannel(blendOp, sg, dg, fsG, fdG); sb = blendChannel(blendOp, sb, db, fsB, fdB);
                    }
                    writePixel(pi, sr | (sg << 8) | (sb << 16) | (dst & 0xff000000));
                }
            }
            if (depthWrite) writeDepth(s, n);
            return n;
        }
        /** One pixel through the whole pipeline in the hardware order (for aliasing depth/colour buffers). Returns pixels written. */
        private int pixel(int pi, int zi, int z, float u, float v, int cr, int cg, int cb, int ca) {
            if (pi < 0 || pi + 4 > fb.length) return 0;
            boolean zok = zb != null && zi >= 0 && zi + 2 <= zb.length;
            if (clear) {
                int dst = readPixel(pi);
                int rgb = (clearMode & 0x100) != 0 ? (cb << 16) | (cg << 8) | cr : dst & 0xffffff, a = (clearMode & 0x200) != 0 ? ca : dst >>> 24;
                writePixel(pi, rgb | (a << 24));
                if (depthWrite && zok) { zb[zi] = (byte)z; zb[zi + 1] = (byte)(z >>> 8); }
                return 1;
            }
            if (depthTest && zok && !test(depthFunc, z, (zb[zi] & 255) | ((zb[zi + 1] & 255) << 8))) return 0;
            int sr = cr, sg = cg, sb = cb, sa = ca;
            if (texture) {
                int tx = (int)Math.floor(through ? u : u * texW), ty = (int)Math.floor(through ? v : v * texH);
                tx = clampU ? (tx < 0 ? 0 : tx >= texW ? texW - 1 : tx) : tx & (texW - 1);
                ty = clampV ? (ty < 0 ? 0 : ty >= texH ? texH - 1 : ty) : ty & (texH - 1);
                int t = texels[ty * texW + tx], tr = t & 255, tg = (t >>> 8) & 255, tb = (t >>> 16) & 255, ta = t >>> 24;
                switch (texFunc) {
                    case 0: sr = cr * tr / 255; sg = cg * tg / 255; sb = cb * tb / 255; sa = texAlpha ? ca * ta / 255 : ca; break;
                    case 1: if (texAlpha) { sr = (tr * ta + cr * (255 - ta)) / 255; sg = (tg * ta + cg * (255 - ta)) / 255; sb = (tb * ta + cb * (255 - ta)) / 255; } else { sr = tr; sg = tg; sb = tb; } break;
                    case 2: sr = (cr * (255 - tr) + envR * tr) / 255; sg = (cg * (255 - tg) + envG * tg) / 255; sb = (cb * (255 - tb) + envB * tb) / 255; sa = texAlpha ? ca * ta / 255 : ca; break;
                    case 3: sr = tr; sg = tg; sb = tb; sa = texAlpha ? ta : ca; break;
                    default: sr = Math.min(255, cr + tr); sg = Math.min(255, cg + tg); sb = Math.min(255, cb + tb); sa = texAlpha ? ca * ta / 255 : ca; break;
                }
                if (texDouble) { sr = Math.min(255, sr * 2); sg = Math.min(255, sg * 2); sb = Math.min(255, sb * 2); }
            }
            if (alphaTest && !test(alphaFunc, sa & alphaMask, alphaRef)) return 0;
            int dst = readPixel(pi);
            if (blend) {
                int dr = dst & 255, dg = (dst >>> 8) & 255, db = (dst >>> 16) & 255, da = dst >>> 24;
                int fsR, fsG, fsB, fdR, fdG, fdB;
                switch (blendSrc) {
                    case 0: fsR = dr; fsG = dg; fsB = db; break; case 1: fsR = 255 - dr; fsG = 255 - dg; fsB = 255 - db; break;
                    case 10: fsR = fixA & 255; fsG = (fixA >>> 8) & 255; fsB = (fixA >>> 16) & 255; break;
                    default: fsR = fsG = fsB = alphaFactor(blendSrc, sa, da);
                }
                switch (blendDst) {
                    case 0: fdR = sr; fdG = sg; fdB = sb; break; case 1: fdR = 255 - sr; fdG = 255 - sg; fdB = 255 - sb; break;
                    case 10: fdR = fixB & 255; fdG = (fixB >>> 8) & 255; fdB = (fixB >>> 16) & 255; break;
                    default: fdR = fdG = fdB = alphaFactor(blendDst, sa, da);
                }
                sr = blendChannel(blendOp, sr, dr, fsR, fdR); sg = blendChannel(blendOp, sg, dg, fsG, fdG); sb = blendChannel(blendOp, sb, db, fsB, fdB);
            }
            if (depthWrite && zok) { zb[zi] = (byte)z; zb[zi + 1] = (byte)(z >>> 8); }
            writePixel(pi, sr | (sg << 8) | (sb << 16) | (dst & 0xff000000));
            return 1;
        }
        private void writeDepth(Span s, int n) {
            byte[] zbuf = zb; int[] ZI = s.zi, Z = s.z;
            for (int i = 0; i < n; i++) { int zi = ZI[i], z = Z[i]; if (zi >= 0 && zi + 2 <= zbuf.length) { zbuf[zi] = (byte)z; zbuf[zi + 1] = (byte)(z >>> 8); } }
        }
        /** Nearest texel fetch and texture function over the span's colours. */
        private void sample(Span s, int n) {
            final int[] R = s.r, G = s.g, B = s.b, A = s.a, T = s.t, tex = texels; final float[] U = s.u, V = s.v;
            final int w = texW, h = texH; final boolean thr = through, cu = clampU, cv = clampV;
            if (generic) fetchGeneric(s, n);
            else for (int i = 0; i < n; i++) {
                int tx = floor(thr ? U[i] : U[i] * w), ty = floor(thr ? V[i] : V[i] * h);
                tx = cu ? (tx < 0 ? 0 : tx >= w ? w - 1 : tx) : tx & (w - 1);
                ty = cv ? (ty < 0 ? 0 : ty >= h ? h - 1 : ty) : ty & (h - 1);
                T[i] = tex[ty * w + tx];
            }
            final boolean ta = texAlpha;
            switch (texFunc) {
                case 0: for (int i = 0; i < n; i++) { int t = T[i]; R[i] = R[i] * (t & 255) / 255; G[i] = G[i] * ((t >>> 8) & 255) / 255; B[i] = B[i] * ((t >>> 16) & 255) / 255; if (ta) A[i] = A[i] * (t >>> 24) / 255; } break;
                case 1: if (ta) for (int i = 0; i < n; i++) { int t = T[i], a = t >>> 24; R[i] = ((t & 255) * a + R[i] * (255 - a)) / 255; G[i] = (((t >>> 8) & 255) * a + G[i] * (255 - a)) / 255; B[i] = (((t >>> 16) & 255) * a + B[i] * (255 - a)) / 255; }
                        else for (int i = 0; i < n; i++) { int t = T[i]; R[i] = t & 255; G[i] = (t >>> 8) & 255; B[i] = (t >>> 16) & 255; } break;
                case 2: for (int i = 0; i < n; i++) { int t = T[i], tr = t & 255, tg = (t >>> 8) & 255, tb = (t >>> 16) & 255; R[i] = (R[i] * (255 - tr) + envR * tr) / 255; G[i] = (G[i] * (255 - tg) + envG * tg) / 255; B[i] = (B[i] * (255 - tb) + envB * tb) / 255; if (ta) A[i] = A[i] * (t >>> 24) / 255; } break;
                case 3: for (int i = 0; i < n; i++) { int t = T[i]; R[i] = t & 255; G[i] = (t >>> 8) & 255; B[i] = (t >>> 16) & 255; if (ta) A[i] = t >>> 24; } break;
                default: for (int i = 0; i < n; i++) { int t = T[i]; R[i] = Math.min(255, R[i] + (t & 255)); G[i] = Math.min(255, G[i] + ((t >>> 8) & 255)); B[i] = Math.min(255, B[i] + ((t >>> 16) & 255)); if (ta) A[i] = A[i] * (t >>> 24) / 255; } break;
            }
            if (texDouble) for (int i = 0; i < n; i++) { R[i] = Math.min(255, R[i] * 2); G[i] = Math.min(255, G[i] * 2); B[i] = Math.min(255, B[i] * 2); }
        }
        private int wrapU(int x) { int w = gW; return clampU ? (x < 0 ? 0 : x >= w ? w - 1 : x) : (w & (w - 1)) == 0 ? x & (w - 1) : Math.floorMod(x, w); }
        private int wrapV(int y) { int h = gH; return clampV ? (y < 0 ? 0 : y >= h ? h - 1 : y) : (h & (h - 1)) == 0 ? y & (h - 1) : Math.floorMod(y, h); }
        /** Texel (x, y) of the general sampler's gW×gH space, in texture format (A in bits 24-31, R in 0-7). */
        private int fetch(int x, int y) {
            if (th == null) return gtex[y * gW + x];
            if (x < thCols && y < thRows) { // high-resolution framebuffer texel
                int i = thBase + y * thPitch + x * thBpp; byte[] b = th;
                if (thBpp == 4) return (b[i] & 255) | ((b[i + 1] & 255) << 8) | ((b[i + 2] & 255) << 16) | (b[i + 3] << 24);
                return convert16((b[i] & 255) | ((b[i + 1] & 255) << 8), thFormat);
            }
            return gtex[(y / thS) * texW + x / thS]; // outside the shadowed rectangle: the 1× texel
        }
        /** Nearest or bilinear fetch through the general sampler into s.t. */
        private void fetchGeneric(Span s, int n) {
            final int[] T = s.t; final float[] U = s.u, V = s.v; final boolean thr = through; final float f = gF; final int w = gW, h = gH;
            if (!bilinear) {
                for (int i = 0; i < n; i++) T[i] = fetch(wrapU(floor(thr ? U[i] * f : U[i] * w)), wrapV(floor(thr ? V[i] * f : V[i] * h)));
                return;
            }
            for (int i = 0; i < n; i++) {
                float fu = (thr ? U[i] * f : U[i] * w) - 0.5f, fv = (thr ? V[i] * f : V[i] * h) - 0.5f; // texel centres at +0.5
                int x0 = floor(fu), y0 = floor(fv), ax = (int)((fu - x0) * 256), ay = (int)((fv - y0) * 256);
                int xa = wrapU(x0), xb = wrapU(x0 + 1), ya = wrapV(y0), yb = wrapV(y0 + 1);
                T[i] = bilerp(fetch(xa, ya), fetch(xb, ya), fetch(xa, yb), fetch(xb, yb), ax, ay);
            }
        }
    }
    /** Bilinear blend of four texels (weights ax, ay in 0..256), per channel including alpha. */
    static int bilerp(int c00, int c10, int c01, int c11, int ax, int ay) {
        if (c00 == c10 && c00 == c01 && c00 == c11) return c00;
        int w00 = (256 - ax) * (256 - ay), w10 = ax * (256 - ay), w01 = (256 - ax) * ay, w11 = ax * ay, out = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int v = ((c00 >>> sh) & 255) * w00 + ((c10 >>> sh) & 255) * w10 + ((c01 >>> sh) & 255) * w01 + ((c11 >>> sh) & 255) * w11;
            out |= ((v + 32768) >>> 16) << sh;
        }
        return out;
    }
    /** The constant clamp255 value of a colour interpolated between c0, c1 and c2 (see triangle), or -1 if it may vary. */
    private static int flatColor(float c0, float c1, float c2) {
        float lo = Math.min(c0, Math.min(c1, c2)), hi = Math.max(c0, Math.max(c1, c2));
        if (!(lo >= -4 && hi <= 4)) return -1;
        float m = (hi - lo) * 0.05f + 1e-5f; int c = clamp255(lo - m);
        return c == clamp255(hi + m) ? c : -1;
    }
    /** (int)Math.floor(f) without the double round trip. */
    private static int floor(float f) { int i = (int)f; return f < i && i != Integer.MIN_VALUE ? i - 1 : i; }
    private static int clamp255(float x) { return x <= 0 ? 0 : x >= 1 ? 255 : (int)(x * 255 + 0.5f); }
    private static int zOf(float z) { return z <= 0 ? 0 : z >= 65535 ? 65535 : (int)z; }
    private static boolean test(int func, int a, int b) {
        switch (func) { case 0: return false; case 1: return true; case 2: return a == b; case 3: return a != b; case 4: return a < b; case 5: return a <= b; case 6: return a > b; default: return a >= b; }
    }
    private static int alphaFactor(int f, int sa, int da) {
        switch (f) {
            case 2: return sa; case 3: return 255 - sa; case 4: return da; case 5: return 255 - da;
            case 6: return Math.min(510, 2 * sa); case 7: return Math.max(0, 255 - 2 * sa); case 8: return Math.min(510, 2 * da); case 9: return Math.max(0, 255 - 2 * da);
            default: return 255;
        }
    }
    private static int blendChannel(int op, int s, int d, int fs, int fd) {
        int v;
        switch (op) {
            case 0: v = (s * fs + d * fd) / 255; break; case 1: v = (s * fs - d * fd) / 255; break; case 2: v = (d * fd - s * fs) / 255; break;
            case 3: v = Math.min(s, d); break; case 4: v = Math.max(s, d); break; default: v = Math.abs(s - d);
        }
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }
    // ---- textures -------------------------------------------------------------------------------
    private final java.util.HashMap<String, CachedTexture> textureCache = new java.util.HashMap<>();
    private int[] clutWords = new int[0]; private int clutHash;
    public long texturesDecoded;
    private void loadClut(int data) {
        int address = ((r[0xb1] & 0xff0000) << 8) | (r[0xb0] & 0xffffff), bytes = (data & 0x3f) * 32;
        if (!m.valid(address, bytes)) return;
        if (dirty(address, bytes)) flush(); // CLUT drawn by pending primitives
        clutWords = new int[bytes / 4]; for (int i = 0; i < clutWords.length; i++) clutWords[i] = m.read32(address + i * 4);
        clutHash = java.util.Arrays.hashCode(clutWords);
    }
    private static int convert16(int p, int format) {
        switch (format) {
            case 0: return 0xff000000 | ((p & 31) * 255 / 31) | (((p >>> 5) & 63) * 255 / 63 << 8) | (((p >>> 11) & 31) * 255 / 31 << 16);
            case 1: return ((p >>> 15) * 255 << 24) | ((p & 31) * 255 / 31) | (((p >>> 5) & 31) * 255 / 31 << 8) | (((p >>> 10) & 31) * 255 / 31 << 16);
            default: return (((p >>> 12) & 15) * 17 << 24) | ((p & 15) * 17) | (((p >>> 4) & 15) * 17 << 8) | (((p >>> 8) & 15) * 17 << 16);
        }
    }
    private int lookupClut(int index) {
        int mode = r[0xc5], format = mode & 3, shift = (mode >>> 2) & 31, mask = (mode >>> 8) & 255, off = ((mode >>> 16) & 31) << 4;
        int i = ((index >>> shift) & mask) | off;
        if (format == 3) return i < clutWords.length ? clutWords[i] : 0;
        int w = (i >>> 1) < clutWords.length ? clutWords[i >>> 1] : 0;
        return convert16((i & 1) == 0 ? w & 0xffff : w >>> 16, format);
    }
    /** Returns the level-0 texture decoded to ABGR texels (A in bits 24-31, R in 0-7), cached per frame. */
    /** Diagnostics: when non-null, each distinct texture used is printed once (FrameDump -Dgetrace=frame). */
    public static volatile java.util.Set<String> trace;
    private int[] texture() {
        int tbw = r[0xa8] & 0xffff, base = ((r[0xa8] & 0x0f0000) << 8) | (r[0xa0] & 0xffffff);
        int w = 1 << (r[0xb8] & 15), h = 1 << ((r[0xb8] >>> 8) & 15), format = r[0xc3] & 15; boolean swizzled = (r[0xc2] & 1) != 0;
        String key = base + ":" + tbw + ":" + w + "x" + h + ":" + format + ":" + swizzled + (format >= 4 && format <= 7 ? ":" + (r[0xc5] & 0xffffff) + ":" + clutHash : "");
        int bits = format == 4 ? 4 : format == 5 ? 8 : format == 3 || format == 7 ? 32 : format == 8 ? 4 : format >= 9 ? 8 : 16;
        int rowBytes = tbw * bits / 8, span = rowBytes * h;
        long sum = checksum(base, span);
        if (trace != null && trace.add(key)) System.out.printf("tex %08x tbw=%d %dx%d fmt=%d swz=%b clut=%08x fb=%08x fbw=%d fbfmt=%d%n", base, tbw, w, h, format, swizzled,
            r[0xb0] & 0xffffff | (r[0xb1] & 0xff0000) << 8, (r[0x9c] & 0xffffff) | ((r[0x9d] & 0xff0000) << 8), r[0x9d] & 0xffff, r[0xd2] & 3);
        CachedTexture cached = textureCache.get(key); if (cached != null && cached.sum == sum) { lastTexture = cached; return cached.texels; }
        int[] out = new int[w * h]; texturesDecoded++;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int value;
            if (format >= 8) value = dxt(base, tbw, x, y, format);
            else {
                int byteX = x * bits / 8, off;
                if (swizzled) { int blocksPerRow = Math.max(1, rowBytes / 16); off = ((y / 8) * blocksPerRow + byteX / 16) * 128 + (y % 8) * 16 + (byteX % 16); }
                else off = y * rowBytes + byteX;
                int a = base + off;
                if (!m.valid(a, Math.max(1, bits / 8))) value = 0;
                else switch (format) {
                    case 3: value = m.read32(a & ~3); break;
                    case 4: { int b = m.read8(a); value = lookupClut((x & 1) == 0 ? b & 15 : b >>> 4); break; }
                    case 5: value = lookupClut(m.read8(a)); break;
                    case 6: value = lookupClut(m.read16(a & ~1)); break;
                    case 7: value = lookupClut(m.read32(a & ~3)); break;
                    default: value = convert16(m.read16(a & ~1), format);
                }
            }
            out[y * w + x] = value;
        }
        CachedTexture entry = new CachedTexture(); entry.sum = sum; entry.texels = out; entry.w = w; entry.h = h;
        if (textureCache.size() > 256) textureCache.clear();
        textureCache.put(key, entry); lastTexture = entry; return out;
    }
    /** A decoded texture; {@code enhanced} is its edge-directed upscale (factor {@code factor}), made on first use. */
    private static final class CachedTexture { long sum; int[] texels, enhanced; int w, h, factor; }
    private CachedTexture lastTexture;
    /** Fast content checksum of guest memory, so cached textures are re-decoded only when their bytes change. */
    private long checksum(int address, int length) {
        // A texture may nominally extend past the end of its memory region (the video player draws a 512×512 8888 frame
        // at VRAM 0x04110000, which runs off the 2 MiB VRAM): hash the mapped part, or the cache would never refresh.
        while (length > 0 && !m.valid(address, length)) length -= Math.min(length, 4096);
        if (length <= 0) return -1;
        byte[] b = m.backing(address, offset); int o = offset[0]; long h = 1125899906842597L;
        int i = 0;
        for (; i + 8 <= length; i += 8) {
            long v = (b[o + i] & 255L) | ((b[o + i + 1] & 255L) << 8) | ((b[o + i + 2] & 255L) << 16) | ((b[o + i + 3] & 255L) << 24)
                | ((b[o + i + 4] & 255L) << 32) | ((b[o + i + 5] & 255L) << 40) | ((b[o + i + 6] & 255L) << 48) | ((long)b[o + i + 7] << 56);
            h = h * 31 + v;
        }
        for (; i < length; i++) h = h * 31 + b[o + i];
        return h;
    }
    private int dxt(int base, int tbw, int x, int y, int format) {
        int blocksPerRow = Math.max(1, tbw / 4), blockSize = format == 8 ? 8 : 16;
        int block = base + ((y / 4) * blocksPerRow + x / 4) * blockSize; if (!m.valid(block, blockSize)) return 0;
        int colorAt = format == 8 ? block : block + 8;
        int c0 = m.read16(colorAt + 4), c1 = m.read16(colorAt + 6), bits = m.read32(colorAt);
        int sel = (bits >>> (((y & 3) * 4 + (x & 3)) * 2)) & 3;
        int[] a = rgb565(c0), b = rgb565(c1); int[] c;
        if (sel == 0) c = a; else if (sel == 1) c = b;
        else if (c0 > c1 || format != 8) c = sel == 2 ? mix(a, b, 2, 1) : mix(a, b, 1, 2);
        else c = sel == 2 ? mix(a, b, 1, 1) : new int[]{0, 0, 0};
        int alpha = 255;
        if (format == 8 && c0 <= c1 && sel == 3) alpha = 0;
        if (format == 9) { long al = m.read64(block); alpha = (int)((al >>> (((y & 3) * 4 + (x & 3)) * 4)) & 15) * 17; }
        if (format == 10) {
            int a0 = m.read8(block + 6), a1 = m.read8(block + 7); long idx = Integer.toUnsignedLong(m.read32(block)) | ((long)m.read16(block + 4) << 32);
            int s = (int)((idx >>> (((y & 3) * 4 + (x & 3)) * 3)) & 7);
            alpha = s == 0 ? a0 : s == 1 ? a1 : a0 > a1 ? ((8 - s) * a0 + (s - 1) * a1) / 7 : s == 6 ? 0 : s == 7 ? 255 : ((6 - s) * a0 + (s - 1) * a1) / 5;
        }
        return (alpha << 24) | (c[2] << 16) | (c[1] << 8) | c[0];
    }
    private static int[] rgb565(int p) { return new int[]{((p >>> 11) & 31) * 255 / 31, ((p >>> 5) & 63) * 255 / 63, (p & 31) * 255 / 31}; }
    private static int[] mix(int[] a, int[] b, int wa, int wb) { return new int[]{(a[0] * wa + b[0] * wb) / (wa + wb), (a[1] * wa + b[1] * wb) / (wa + wb), (a[2] * wa + b[2] * wb) / (wa + wb)}; }

    private void blockTransfer(int data) {
        flush(); // earlier drawing may be the transfer source
        int src = ((r[0xb3] & 0xff0000) << 8) | (r[0xb2] & 0xffffff), srcW = r[0xb3] & 0xffff;
        int dst = ((r[0xb5] & 0xff0000) << 8) | (r[0xb4] & 0xffffff), dstW = r[0xb5] & 0xffff;
        int sx = r[0xeb] & 0x3ff, sy = (r[0xeb] >>> 10) & 0x3ff, dx = r[0xec] & 0x3ff, dy = (r[0xec] >>> 10) & 0x3ff;
        int w = (r[0xee] & 0x3ff) + 1, h = ((r[0xee] >>> 10) & 0x3ff) + 1, bpp = (data & 1) != 0 ? 4 : 2;
        Shadow hs = null, hd = null; int[] so = null, dO = null;
        if (scale > 1) { // framebuffer-to-framebuffer copies keep their high-resolution pixels
            hs = shadowAt(src, srcW, bpp); hd = shadowAt(dst, dstW, bpp);
            if (hs != null && hd != null && hs.format == hd.format) { sync(hs); sync(hd); so = origin(hs, src); dO = origin(hd, dst); } else hs = hd = null;
        }
        for (int y = 0; y < h; y++) {
            int from = src + ((sy + y) * srcW + sx) * bpp, to = dst + ((dy + y) * dstW + dx) * bpp;
            if (m.valid(from, w * bpp) && m.valid(to, w * bpp)) m.copy(to, from, w * bpp);
        }
        if (hd != null) copyHighResolution(hs, so[0] + sx, so[1] + sy, hd, dO[0] + dx, dO[1] + dy, w, h);
    }

    // ---- internal resolution: high-resolution shadow targets ------------------------------------
    //
    // At render scale k (> 1) every framebuffer and depth buffer the GE draws into gets a shadow: the same pixel format
    // and layout with k× the columns and rows (byte[] hi). States that draw into it get k× vertex positions and scissor,
    // so the unchanged rasteriser fills the shadow. VRAM stays the guest's 1× picture:
    //  - resolve: after every flush the rectangle drawn at high resolution is box-filtered (colour) or point-sampled
    //    (depth) back into VRAM, so the CPU, the display, block transfers, CLUT loads and 1× texture reads see a coherent
    //    480×272 picture;
    //  - sync: lo mirrors the VRAM bytes as of the last resolve. Memory reports which VRAM bytes were written since
    //    (CPU, DMA, video, block transfers, 1× fallback draws); before a shadow is drawn, sampled or shown, pixels whose
    //    VRAM bytes differ from lo are copied up (k×k replication).
    //  - textures: a texture whose address, buffer width and format match a shadow samples the shadow's pixels (at k×
    //    the texel density); anything else (CLUT or swizzled views, other strides, the target itself) reads 1× VRAM.
    /** Render scale (1 = native 480×272). */
    private int scale = 1;
    /**
     * Texture filtering override: 0 off (nearest, as the renderer always did), 1 bilinear, 2 enhanced (edge-directed
     * Scale2x-style upscale of the decoded texture, 4× for textures up to 128×128 and 2× up to 256×256, then bilinear).
     */
    public volatile int textureFilter;
    private final byte[] vramArray;
    static final class Shadow {
        final int base, stride, bpp, format, k; // VRAM offset of row 0, pixels per row, bytes per pixel, fb format (0-3; -1 depth), scale
        int rows; byte[] hi, lo; int version; long used, readIn = -1;
        int chkLo = Integer.MAX_VALUE, chkHi = Integer.MIN_VALUE; // VRAM bytes (offsets) to compare with lo
        int dx0 = Integer.MAX_VALUE, dx1 = -1, dy0 = Integer.MAX_VALUE, dy1 = -1; // guest pixels drawn since the last resolve
        Shadow(int base, int stride, int bpp, int format, int k) { this.base = base; this.stride = stride; this.bpp = bpp; this.format = format; this.k = k; }
        int pitch() { return stride * bpp; }
        int end() { return base + rows * pitch(); }
        int hiPitch() { return stride * k * bpp; }
    }
    private final java.util.ArrayList<Shadow> shadows = new java.util.ArrayList<>();
    /** Host time spent writing high-resolution drawing back to VRAM (diagnostics). */
    public long resolveNanos;
    private long batch; private int batchMode; // flush counter; kind of state pending in this batch: 0 none, 1 shadow, 2 direct
    private final int[] writes = new int[2];
    public int scale() { return scale; }
    /** Bytes held by shadow buffers (diagnostics). */
    public long shadowBytes() { long n = 0; for (Shadow sh : shadows) n += sh.hi.length + sh.lo.length; return n; }
    /**
     * Sets the internal render scale (1 = native). Call on the emulation thread between display lists; pending drawing is
     * flushed and existing shadows are dropped (VRAM already holds their resolved pictures).
     */
    /** Frame generation's motion recorder, or null when frame generation is off (the default). */
    private volatile MotionVectors motion;
    public void setMotion(MotionVectors m) { motion = m; }
    public MotionVectors motion() { return motion; }

    public void setScale(int k) {
        k = Math.max(1, Math.min(16, k)); if (k == scale) return;
        flush(); shadows.clear(); scale = k; configure();
    }

    /** Switches state s to its shadow targets, or leaves it drawing VRAM directly (1×) when they cannot be used. */
    private void highResolution(Raster s) {
        boolean useDepth = s.zb != null && (s.depthTest || s.depthWrite);
        boolean shadowable = s.fb == vramArray && !s.aliased && (!useDepth || s.zb == vramArray);
        int mode = shadowable ? 1 : 2;
        if (batchMode != 0 && batchMode != mode) flush(); // keep 1× and high-resolution drawing of one batch in order
        if (!shadowable) { s.direct = true; batchMode = 2; return; }
        int bpp = s.fbFormat == 3 ? 4 : 2, rows = Math.min(s.sy1 + 1, 272); // grows later if drawing goes lower
        Shadow cs = shadow(s.fbBase, s.fbStride, bpp, s.fbFormat, rows), zs = useDepth ? shadow(s.zbBase, s.zbStride, 2, -1, rows) : null;
        if (cs == null || useDepth && zs == null || !shadows.contains(cs)) { s.direct = true; batchMode = 2; return; }
        if ((cs.readIn == batch || zs != null && zs.readIn == batch) && !jobs.isEmpty()) flush(); // pending drawing samples it
        sync(cs); if (zs != null) sync(zs);
        int k = scale; s.scale = k; s.cs = cs; s.zs = zs;
        s.fb = cs.hi; s.fbBase = 0; s.fbStride *= k;
        if (zs != null) { s.zb = zs.hi; s.zbBase = 0; s.zbStride *= k; } else s.zb = null;
        s.sx0 *= k; s.sy0 *= k; s.sx1 = (s.sx1 + 1) * k - 1; s.sy1 = (s.sy1 + 1) * k - 1;
        batchMode = 1; // (shadow creation above may have flushed)
    }
    /** The shadow of the buffer at VRAM offset base (created from VRAM if needed), or null if it cannot have one. */
    private Shadow shadow(int base, int stride, int bpp, int format, int rows) {
        if (stride <= 0) return null;
        for (Shadow sh : shadows) if (sh.base == base && sh.stride == stride && sh.bpp == bpp && sh.format == format) { sh.used = batch; return sh; }
        int pitch = stride * bpp; rows = Math.min(rows, (Memory.VRAM_SIZE - base) / pitch);
        for (Shadow sh : shadows) if (sh.base > base) rows = Math.min(rows, (sh.base - base) / pitch); // stop short of the next one
        if (rows < 1) rows = Math.min(1, (Memory.VRAM_SIZE - base) / pitch);
        if (rows < 1) return null;
        long bytes = (long)rows * pitch * (scale * scale + 1);
        if (bytes > Integer.MAX_VALUE / 2) return null;
        evict(base, base + rows * pitch, bytes);
        Shadow sh = new Shadow(base, stride, bpp, format, scale); sh.used = batch;
        sh.rows = rows; sh.lo = java.util.Arrays.copyOfRange(vramArray, base, base + rows * pitch); sh.hi = new byte[rows * scale * sh.hiPitch()];
        upscaleRows(sh, 0, rows);
        shadows.add(sh); return sh;
    }
    /** Drops shadows overlapping VRAM [lo, hi) and, above the memory budget, the least recently used ones. */
    private void evict(int lo, int hi, long adding) {
        boolean pending = !jobs.isEmpty();
        long budget = Runtime.getRuntime().maxMemory() / 3, total = adding; for (Shadow sh : shadows) total += sh.hi.length + sh.lo.length;
        for (int i = shadows.size() - 1; i >= 0; i--) {
            Shadow sh = shadows.get(i);
            if (sh.base < hi && lo < sh.end()) { if (pending) { flush(); pending = false; } shadows.remove(i); total -= sh.hi.length + sh.lo.length; }
        }
        while (total > budget && !shadows.isEmpty()) {
            Shadow old = shadows.get(0); for (Shadow sh : shadows) if (sh.used < old.used) old = sh;
            if (old.used == batch) break; // everything left is in use by this batch
            if (pending) { flush(); pending = false; }
            shadows.remove(old); total -= old.hi.length + old.lo.length;
        }
    }
    /** Replicates guest rows [y0, y1) of lo into the shadow (k×k per pixel). */
    private static void upscaleRows(Shadow sh, int y0, int y1) {
        int k = sh.k, bpp = sh.bpp, pitch = sh.pitch(), hp = sh.hiPitch(); byte[] lo = sh.lo, hi = sh.hi;
        for (int y = y0; y < y1; y++) {
            int row = y * k * hp;
            for (int x = 0, src = y * pitch; x < sh.stride; x++, src += bpp)
                for (int i = 0, dst = row + x * k * bpp; i < k; i++, dst += bpp) System.arraycopy(lo, src, hi, dst, bpp);
            for (int i = 1; i < k; i++) System.arraycopy(hi, row, hi, row + i * hp, hp);
        }
    }
    /** Makes room for guest rows [0, need) in the shadows of state s (flushing first); neighbours in the way are dropped. */
    private void grow(Raster s, int need) {
        flush();
        for (Shadow sh : new Shadow[]{s.cs, s.zs}) {
            if (sh == null || need <= sh.rows) continue;
            int pitch = sh.pitch(), rows = Math.min(Math.max(need, (sh.rows + 15) & ~15), (Memory.VRAM_SIZE - sh.base) / pitch);
            Shadow partner = sh == s.cs ? s.zs : s.cs;
            if (partner != null && partner.base > sh.base) rows = Math.min(rows, (partner.base - sh.base) / pitch); // never over its own depth buffer
            if (rows <= sh.rows) continue;
            for (int i = shadows.size() - 1; i >= 0; i--) { Shadow o = shadows.get(i); if (o != sh && o != partner && o.base < sh.base + rows * pitch && sh.base < o.end()) shadows.remove(i); }
            int old = sh.rows; sh.rows = rows;
            sh.hi = java.util.Arrays.copyOf(sh.hi, rows * sh.k * sh.hiPitch()); sh.lo = java.util.Arrays.copyOf(sh.lo, rows * pitch);
            System.arraycopy(vramArray, sh.base + old * pitch, sh.lo, old * pitch, (rows - old) * pitch);
            upscaleRows(sh, old, rows); sh.version++;
        }
        s.fb = s.cs.hi; if (s.zs != null) s.zb = s.zs.hi; batchMode = 1;
    }
    private static void drawn(Shadow sh, int x0, int x1, int y0, int y1) {
        sh.dx0 = Math.min(sh.dx0, x0); sh.dx1 = Math.max(sh.dx1, x1); sh.dy0 = Math.min(sh.dy0, y0); sh.dy1 = Math.max(sh.dy1, y1);
    }
    /** Distributes the VRAM writes Memory recorded since the last call to the shadows they touch. */
    private void collectWrites() {
        if (!m.takeVramWrites(writes)) return;
        for (Shadow sh : shadows) {
            int lo = Math.max(writes[0], sh.base), hi = Math.min(writes[1], sh.end());
            if (lo < hi) { sh.chkLo = Math.min(sh.chkLo, lo); sh.chkHi = Math.max(sh.chkHi, hi); }
        }
    }
    /** Brings the shadow up to date with VRAM bytes written behind the renderer's back (see the section comment). */
    private void sync(Shadow sh) {
        collectWrites(); if (sh.chkLo >= sh.chkHi) return;
        int bpp = sh.bpp, from = (sh.chkLo - sh.base) / bpp, to = Math.min(sh.rows * sh.stride, (sh.chkHi - sh.base + bpp - 1) / bpp);
        sh.chkLo = Integer.MAX_VALUE; sh.chkHi = Integer.MIN_VALUE;
        byte[] v = vramArray, lo = sh.lo, hi = sh.hi; int k = sh.k, hp = sh.hiPitch(); boolean changed = false;
        for (int p = from; p < to; p++) {
            int o = p * bpp, vo = sh.base + o;
            if (v[vo] == lo[o] && v[vo + 1] == lo[o + 1] && (bpp == 2 || v[vo + 2] == lo[o + 2] && v[vo + 3] == lo[o + 3])) continue;
            System.arraycopy(v, vo, lo, o, bpp); changed = true;
            int x = p % sh.stride, y = p / sh.stride, row = y * k * hp + x * k * bpp;
            for (int j = 0; j < k; j++) for (int i = 0; i < k; i++) System.arraycopy(v, vo, hi, row + j * hp + i * bpp, bpp);
        }
        if (changed) sh.version++;
    }
    /** Writes the rectangle drawn at high resolution back to VRAM (and lo): box filter for colour, top-left sample for depth. */
    private void resolve() {
        for (Shadow sh : shadows) {
            if (sh.dy1 < 0) continue;
            int x0 = Math.max(0, sh.dx0), x1 = Math.min(sh.stride - 1, sh.dx1), y0 = Math.max(0, sh.dy0), y1 = Math.min(sh.rows - 1, sh.dy1);
            sh.dx0 = sh.dy0 = Integer.MAX_VALUE; sh.dx1 = sh.dy1 = -1; sh.version++;
            if (x0 > x1 || y0 > y1) continue;
            int rows = y1 - y0 + 1;
            if (WORKERS == 1 || (long)rows * (x1 - x0 + 1) * sh.k * sh.k < 65536) { resolveRows(sh, x0, x1, y0, y1); continue; }
            int parts = WORKERS + 1, step = (rows + parts - 1) / parts; // row bands in parallel; the calling thread takes the first
            java.util.List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
            for (int p = 1; p < parts; p++) { int a = y0 + p * step, b = Math.min(y1, a + step - 1); if (a <= b) done.add(POOL.submit(() -> resolveRows(sh, x0, x1, a, b))); }
            resolveRows(sh, x0, x1, y0, Math.min(y1, y0 + step - 1));
            for (java.util.concurrent.Future<?> f : done) try { f.get(); } catch (Exception e) { throw new IllegalStateException("GE resolve failed", e); }
        }
    }
    private void resolveRows(Shadow sh, int x0, int x1, int y0, int y1) {
        int k = sh.k, n = k * k, bpp = sh.bpp, pitch = sh.pitch(), hp = sh.hiPitch(); byte[] hi = sh.hi, lo = sh.lo, v = vramArray;
        for (int y = y0; y <= y1; y++) {
            int hrow = y * k * hp;
            for (int x = x0; x <= x1; x++) {
                int o = y * pitch + x * bpp, h0 = hrow + x * k * bpp;
                if (sh.format < 0) { lo[o] = hi[h0]; lo[o + 1] = hi[h0 + 1]; }
                else if (bpp == 4) {
                    int r = 0, g = 0, b = 0, a = 0;
                    for (int j = 0; j < k; j++) for (int i = 0, q = h0 + j * hp; i < k; i++, q += 4) { r += hi[q] & 255; g += hi[q + 1] & 255; b += hi[q + 2] & 255; a += hi[q + 3] & 255; }
                    int half = n >> 1; lo[o] = (byte)((r + half) / n); lo[o + 1] = (byte)((g + half) / n); lo[o + 2] = (byte)((b + half) / n); lo[o + 3] = (byte)((a + half) / n);
                } else {
                    // 16-bit formats: average each bit field (565: 5/6/5; 5551: 5/5/5/1; 4444: 4/4/4/4).
                    int f = sh.format, s1 = f == 0 ? 5 : f == 1 ? 5 : 4, s2 = f == 0 ? 11 : f == 1 ? 10 : 8, s3 = f == 0 ? 16 : f == 1 ? 15 : 12;
                    int m0 = (1 << s1) - 1, m1 = (1 << (s2 - s1)) - 1, m2 = (1 << (s3 - s2)) - 1, m3 = (1 << (16 - s3)) - 1;
                    int c0 = 0, c1 = 0, c2 = 0, c3 = 0;
                    for (int j = 0; j < k; j++) for (int i = 0, q = h0 + j * hp; i < k; i++, q += 2) {
                        int p = (hi[q] & 255) | ((hi[q + 1] & 255) << 8); c0 += p & m0; c1 += (p >>> s1) & m1; c2 += (p >>> s2) & m2; c3 += (p >>> s3) & m3;
                    }
                    int half = n >> 1, p = (c0 + half) / n | ((c1 + half) / n) << s1 | ((c2 + half) / n) << s2 | ((c3 + half) / n) << s3;
                    lo[o] = (byte)p; lo[o + 1] = (byte)(p >>> 8);
                }
            }
            System.arraycopy(lo, y * pitch + x0 * bpp, v, sh.base + y * pitch + x0 * bpp, (x1 - x0 + 1) * bpp);
        }
    }
    /** The colour shadow containing VRAM address a with this row width and pixel size, or null. */
    private Shadow shadowAt(int a, int stride, int bpp) {
        if (!m.valid(a, bpp) || m.backing(a, offset) != vramArray) return null; int o = offset[0];
        for (Shadow sh : shadows) if (sh.format >= 0 && sh.stride == stride && sh.bpp == bpp && o >= sh.base && o < sh.end() && (o - sh.base) % bpp == 0) return sh;
        return null;
    }
    /** Pixel (x, y) of shadow sh that VRAM address a corresponds to. */
    private int[] origin(Shadow sh, int a) { m.backing(a, offset); int p = (offset[0] - sh.base) / sh.bpp; return new int[]{p % sh.stride, p / sh.stride}; }
    /** Block transfer between two shadowed framebuffers: copies the high-resolution pixels, which VRAM already has at 1×. */
    private void copyHighResolution(Shadow from, int sx, int sy, Shadow to, int dx, int dy, int w, int h) {
        if (sx + w > from.stride || dx + w > to.stride) return; // wraps rows: VRAM has it, sync replicates it
        int k = scale, bpp = to.bpp, fp = from.hiPitch(), tp = to.hiPitch();
        collectWrites(); // the 1× copy just written: mark it synced where the high-resolution copy covers it
        for (int y = 0; y < h; y++) {
            if (sy + y >= from.rows || dy + y >= to.rows) break;
            for (int j = 0; j < k; j++) System.arraycopy(from.hi, ((sy + y) * k + j) * fp + sx * k * bpp, to.hi, ((dy + y) * k + j) * tp + dx * k * bpp, w * k * bpp);
            System.arraycopy(vramArray, to.base + (dy + y) * to.pitch() + dx * bpp, to.lo, (dy + y) * to.pitch() + dx * bpp, w * bpp);
        }
        to.version++;
    }

    /** Chooses how state s samples its texture: a framebuffer shadow, the enhanced upscale, or a bilinear filter. */
    private void textureOptions(Raster s) {
        int filter = textureFilter, format = r[0xc3] & 15, tbw = r[0xa8] & 0xffff, addr = ((r[0xa8] & 0x0f0000) << 8) | (r[0xa0] & 0xffffff);
        Shadow ts = null;
        if (scale > 1 && format <= 3 && (r[0xc2] & 1) == 0) { ts = shadowAt(addr, tbw, format == 3 ? 4 : 2); if (ts != null && (ts.format != format || ts == s.cs)) ts = null; }
        s.bilinear = filter != 0;
        if (ts != null) { // render-to-texture at high resolution
            sync(ts); ts.readIn = batch; int[] o = origin(ts, addr); int k = scale;
            s.th = ts.hi; s.thS = k; s.thBpp = ts.bpp; s.thFormat = format; s.thPitch = ts.hiPitch();
            s.thBase = o[1] * k * s.thPitch + o[0] * k * ts.bpp;
            s.thCols = Math.max(0, Math.min(s.texW, ts.stride - o[0])) * k; s.thRows = Math.max(0, Math.min(s.texH, ts.rows - o[1])) * k;
            s.gtex = s.texels; s.gW = s.texW * k; s.gH = s.texH * k; s.gF = k; s.generic = true;
            return;
        }
        if (filter == 0) return;
        s.gtex = s.texels; s.gW = s.texW; s.gH = s.texH; s.gF = 1; s.generic = true;
        CachedTexture e = lastTexture;
        if (filter == 2 && e != null && e.texels == s.texels) {
            if (e.enhanced == null && e.w * e.h <= 256 * 256) { e.enhanced = scale2x(e.texels, e.w, e.h); e.factor = 2; if (e.w * e.h <= 128 * 128) { e.enhanced = scale2x(e.enhanced, e.w * 2, e.h * 2); e.factor = 4; } }
            if (e.enhanced != null) { s.gtex = e.enhanced; s.gW = e.w * e.factor; s.gH = e.h * e.factor; s.gF = e.factor; }
        }
    }
    /**
     * Scale2x (the public EPX/AdvMAME2x rule) with a colour tolerance and blending: each texel becomes 2×2, and a sub-texel
     * moves halfway to the colour of the two neighbours meeting at its corner when they match each other but not the
     * opposite neighbours. Diagonal edges of icons and fonts become diagonal instead of stair-stepped; the bilinear
     * sampler smooths the rest.
     */
    static int[] scale2x(int[] src, int w, int h) {
        int[] out = new int[w * h * 4]; int ow = w * 2;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int e = src[y * w + x], b = y > 0 ? src[(y - 1) * w + x] : e, hh = y < h - 1 ? src[(y + 1) * w + x] : e;
            int d = x > 0 ? src[y * w + x - 1] : e, f = x < w - 1 ? src[y * w + x + 1] : e;
            int e0 = e, e1 = e, e2 = e, e3 = e;
            if (!near(b, hh) && !near(d, f)) {
                if (near(d, b)) e0 = mid(e, d); if (near(b, f)) e1 = mid(e, f); if (near(d, hh)) e2 = mid(e, d); if (near(hh, f)) e3 = mid(e, f);
            }
            int o = y * 2 * ow + x * 2; out[o] = e0; out[o + 1] = e1; out[o + ow] = e2; out[o + ow + 1] = e3;
        }
        return out;
    }
    /** Per-channel average: the corner is only half taken over, so anti-aliased art gets a soft diagonal, not a new step. */
    private static int mid(int a, int b) { return (a & b) + (((a ^ b) & 0xfefefefe) >>> 1); }
    private static boolean near(int a, int b) {
        if (a == b) return true;
        for (int sh = 0; sh < 32; sh += 8) if (Math.abs(((a >>> sh) & 255) - ((b >>> sh) & 255)) > 24) return false;
        return true;
    }

    // ---- presentation ---------------------------------------------------------------------------------
    /** Size of the picture returned by the last {@link #frame} call. */
    public int frameWidth, frameHeight;
    private int[] frameOut = new int[0], frameTmp = new int[0];
    /**
     * The displayed framebuffer as opaque ARGB pixels for the frontend, at render resolution: (480·scale/ssaa) ×
     * (272·scale/ssaa) when the buffer has a shadow, else 480×272 from VRAM. {@code ssaa} (1, 2, ...) box-filters that
     * many rendered pixels per output pixel (supersampling); {@code smooth} applies FXAA-style edge smoothing. The array
     * is reused by the next call. Returns null if the address is not readable. Call on the emulation thread.
     */
    public int[] frame(int address, int stride, int format, int ssaa, boolean smooth) {
        if (stride <= 0 || !m.valid(address, 4)) return null;
        flush();
        byte[] arr = m.backing(address, offset); int base = offset[0], bpp = format == 3 ? 4 : 2;
        Shadow sh = null;
        if (arr == vramArray) for (Shadow x : shadows) if (x.base == base && x.stride == stride && x.format == format) sh = x;
        if (sh != null) sync(sh);
        int k = sh != null ? scale : 1, q = sh != null && ssaa > 1 && scale % ssaa == 0 ? ssaa : 1, w = 480 * k / q, h = 272 * k / q;
        if (frameOut.length < w * h) frameOut = new int[w * h];
        int[] out = frameOut; frameWidth = w; frameHeight = h;
        byte[] src = sh != null ? sh.hi : arr; int pitch = sh != null ? sh.hiPitch() : stride * bpp, o0 = sh != null ? 0 : base, validRows = sh != null ? sh.rows * k : 272;
        int n = q * q, half = n >> 1;
        for (int y = 0; y < h; y++) {
            if (q == 1 && format == 3 && y < validRows) { // fast path: 8888 rows the GE drew
                for (int x = 0, i = o0 + y * pitch, d = y * w; x < w; x++, i += 4, d++) out[d] = 0xff000000 | (src[i] & 255) << 16 | (src[i + 1] & 255) << 8 | (src[i + 2] & 255);
                continue;
            }
            for (int x = 0; x < w; x++) {
                int r = 0, g = 0, b = 0;
                for (int j = 0; j < q; j++) for (int i = 0; i < q; i++) {
                    int hx = x * q + i, hy = y * q + j, c;
                    if (hy < validRows) c = argb(src, o0 + hy * pitch + hx * bpp, format);
                    else c = argb(arr, base + (hy / k) * stride * bpp + (hx / k) * bpp, format); // rows the GE never drew: VRAM
                    r += (c >>> 16) & 255; g += (c >>> 8) & 255; b += c & 255;
                }
                out[y * w + x] = n == 1 ? 0xff000000 | r << 16 | g << 8 | b : 0xff000000 | ((r + half) / n) << 16 | ((g + half) / n) << 8 | (b + half) / n;
            }
        }
        if (smooth) smoothEdges(out, w, h);
        return out;
    }
    /** One framebuffer pixel as opaque ARGB (the same expansion as HleSystem.snapshot); 0 outside the array. */
    private static int argb(byte[] a, int i, int format) {
        if (i < 0 || i + (format == 3 ? 4 : 2) > a.length) return 0xff000000;
        if (format == 3) return 0xff000000 | (a[i] & 255) << 16 | (a[i + 1] & 255) << 8 | (a[i + 2] & 255);
        int p = (a[i] & 255) | (a[i + 1] & 255) << 8, r, g, b;
        switch (format) {
            case 0: r = (p & 31) << 3; g = ((p >>> 5) & 63) << 2; b = ((p >>> 11) & 31) << 3; break;
            case 1: r = (p & 31) << 3; g = ((p >>> 5) & 31) << 3; b = ((p >>> 10) & 31) << 3; break;
            default: r = (p & 15) << 4; g = ((p >>> 4) & 15) << 4; b = ((p >>> 8) & 15) << 4; break;
        }
        return 0xff000000 | r << 16 | g << 8 | b;
    }
    /**
     * Edge smoothing in the spirit of FXAA's sub-pixel pass (a simplified original implementation): where the local luma
     * contrast is high, the pixel is blended toward the average of its 3×3 neighbourhood, more strongly the more it stands
     * out from its four direct neighbours. Flat areas and soft gradients are untouched.
     */
    private void smoothEdges(int[] px, int w, int h) {
        if (frameTmp.length < w * h) frameTmp = new int[w * h];
        int[] src = frameTmp; System.arraycopy(px, 0, src, 0, w * h);
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int i = y * w + x, c = src[i], n = src[i - w], s = src[i + w], e = src[i + 1], wv = src[i - 1];
            int lc = luma(c), ln = luma(n), ls = luma(s), le = luma(e), lw = luma(wv);
            int lo = Math.min(lc, Math.min(Math.min(ln, ls), Math.min(le, lw))), hi = Math.max(lc, Math.max(Math.max(ln, ls), Math.max(le, lw)));
            int range = hi - lo; if (range < Math.max(16, hi >> 3)) continue;
            int avg = (ln + ls + le + lw) >> 2; float sub = Math.min(1f, Math.abs(avg - lc) / (float)range);
            float amount = sub * sub * (3 - 2 * sub) * 0.75f; if (amount <= 0.02f) continue;
            int nw = src[i - w - 1], ne = src[i - w + 1], sw = src[i + w - 1], se = src[i + w + 1];
            int wgt = (int)(amount * 256), keep = 256 - wgt, out = 0xff000000;
            for (int sh = 0; sh < 24; sh += 8) {
                int blur = (2 * (((n >>> sh) & 255) + ((s >>> sh) & 255) + ((e >>> sh) & 255) + ((wv >>> sh) & 255)) + ((nw >>> sh) & 255) + ((ne >>> sh) & 255) + ((sw >>> sh) & 255) + ((se >>> sh) & 255) + 6) / 12;
                out |= ((((c >>> sh) & 255) * keep + blur * wgt + 128) >> 8) << sh;
            }
            px[i] = out;
        }
    }
    private static int luma(int c) { return (((c >>> 16) & 255) * 77 + ((c >>> 8) & 255) * 150 + (c & 255) * 29) >> 8; }
}
