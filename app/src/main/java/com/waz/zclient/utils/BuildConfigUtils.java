/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import com.waz.api.AvsLogLevel;
import com.waz.api.LogLevel;
import com.waz.zclient.BuildConfig;
import com.waz.zclient.R;
import timber.log.Timber;

import java.io.File;

public class BuildConfigUtils {

    public static final String TAG = BuildConfigUtils.class.getName();

    public static boolean isHockeyUpdateEnabled() {
        return BuildConfig.USE_HOCKEY_UPDATE && !fileExists("disableHockeyUpdates");
    }

    public static boolean isLocalBuild(Context context) {
        boolean localVersionCode = false;
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            localVersionCode = packageInfo.versionCode == 1;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return 0 != (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) && localVersionCode;
    }

    private static boolean fileExists(String fileName) {
        try {
            File path = Environment.getExternalStorageDirectory();
            File file = new File(path, fileName);
            Timber.i("Build config file %s exists: %b", file.getAbsolutePath(), file.exists());
            return file.exists();
        } catch (Exception e) {
            Timber.e(e, "Something went wrong");
        }
        return false;
    }

    public static LogLevel getLogLevelSE(Context context) {
        if (BuildConfig.LOG_LEVEL_SE == context.getResources().getInteger(R.integer.log_level_supress)) {
            return LogLevel.SUPPRESS;
        }
        if (BuildConfig.LOG_LEVEL_SE == context.getResources().getInteger(R.integer.log_level_verbose)) {
            return LogLevel.VERBOSE;
        }
        if (BuildConfig.LOG_LEVEL_SE == context.getResources().getInteger(R.integer.log_level_debug)) {
            return LogLevel.DEBUG;
        }
        if (BuildConfig.LOG_LEVEL_SE == context.getResources().getInteger(R.integer.log_level_info)) {
            return LogLevel.INFO;
        }
        if (BuildConfig.LOG_LEVEL_SE == context.getResources().getInteger(R.integer.log_level_warn)) {
            return LogLevel.WARN;
        }
        if (BuildConfig.LOG_LEVEL_SE == context.getResources().getInteger(R.integer.log_level_error)) {
            return LogLevel.ERROR;
        }
        return LogLevel.ASSERT;

    }

    public static AvsLogLevel getLogLevelAVS(Context context) {
        if (BuildConfig.LOG_LEVEL_AVS == context.getResources().getInteger(R.integer.log_level_verbose)) {
            return AvsLogLevel.DEBUG;
        }
        if (BuildConfig.LOG_LEVEL_AVS == context.getResources().getInteger(R.integer.log_level_debug)) {
            return AvsLogLevel.DEBUG;
        }
        if (BuildConfig.LOG_LEVEL_AVS == context.getResources().getInteger(R.integer.log_level_info)) {
            return AvsLogLevel.INFO;
        }
        if (BuildConfig.LOG_LEVEL_AVS == context.getResources().getInteger(R.integer.log_level_warn)) {
            return AvsLogLevel.WARN;
        }
        if (BuildConfig.LOG_LEVEL_AVS == context.getResources().getInteger(R.integer.log_level_error)) {
            return AvsLogLevel.ERROR;
        }
        return AvsLogLevel.ERROR;
    }

}
