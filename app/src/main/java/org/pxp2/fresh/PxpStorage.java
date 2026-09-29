package org.pxp2.fresh;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import java.io.File;

/**
 * Chooses the PSP Memory Stick folder. With "All files access" it is /storage/emulated/0/PXP (visible to file
 * managers and to a PC over USB); otherwise the app's own external files folder is used as a fallback.
 */
final class PxpStorage {
    static final String[] FOLDERS = {"PSP/GAME", "PSP/SAVEDATA", "PSP/COMMON", "PSP/SYSTEM", "PSP/THEME", "SEPLUGINS", "ISO", "MUSIC", "PICTURE", "VIDEO"};
    static boolean sharedAccess() { return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : true; }
    static File memoryStick(Context c) {
        File root = sharedAccess() ? new File(Environment.getExternalStorageDirectory(), "PXP") : new File(c.getExternalFilesDir(null), "PXP");
        for (String f : FOLDERS) new File(root, f).mkdirs();
        return root;
    }
    private PxpStorage() { }
}
