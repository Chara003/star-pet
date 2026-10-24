package me.rerere.pet.data;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 位置获取：使用系统 LocationManager（不依赖 Google Play 服务）。
 * 需用户授权定位权限；未授权时工具不启用。
 */
public final class LocationHelper {

    /** 等待定位的最长时间。 */
    private static final long TIMEOUT_MS = 10_000L;

    private static Context appContext;

    private LocationHelper() {}

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    /** 是否已获得定位权限。 */
    public static boolean hasPermission() {
        if (appContext == null) return false;
        try {
            int fine = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
            int coarse = appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
            return fine == PackageManager.PERMISSION_GRANTED
                    || coarse == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 定位服务是否开启。 */
    public static boolean isProviderEnabled() {
        if (appContext == null) return false;
        try {
            LocationManager lm = (LocationManager)
                    appContext.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 获取位置描述文本（阻塞，最多约 10 秒）。
     * 返回经纬度；若反向地理编码成功，附带地址。
     */
    public static String describe() {
        if (appContext == null) return "定位不可用：应用未初始化";
        if (!hasPermission()) return "未获得定位权限，无法获取位置。";
        try {
            LocationManager lm = (LocationManager)
                    appContext.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return "定位不可用：系统服务缺失";
            if (!isProviderEnabled()) return "定位服务未开启，请在系统设置中打开定位。";

            Location loc = lastKnown(lm);
            if (loc == null) {
                loc = awaitUpdate(lm);
            }
            if (loc == null) return "定位超时，请稍后重试。";

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "纬度 %.6f，经度 %.6f", loc.getLatitude(), loc.getLongitude()));
            if (loc.hasAccuracy()) {
                sb.append(String.format(Locale.US, "（精度约 %.0f 米）", loc.getAccuracy()));
            }
            String address = reverseGeocode(loc.getLatitude(), loc.getLongitude());
            if (address != null && !address.isEmpty()) {
                sb.append("\n大致位置：").append(address);
            }
            return sb.toString();
        } catch (SecurityException se) {
            return "未获得定位权限，无法获取位置。";
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
            return "定位失败：" + msg;
        }
    }

    /** 先取最近一次已知位置（最快）。 */
    private static Location lastKnown(LocationManager lm) {
        try {
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            // 超过 10 分钟的旧位置不作为最终结果，但仍可用于兜底
            if (best != null && System.currentTimeMillis() - best.getTime() < 10 * 60_000L) {
                return best;
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 请求一次定位更新，超时返回 null。 */
    private static Location awaitUpdate(LocationManager lm) {
        final CountDownLatch latch = new CountDownLatch(1);
        final Location[] holder = new Location[1];
        final LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (location != null) {
                    holder[0] = location;
                    latch.countDown();
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        try {
            String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    ? LocationManager.NETWORK_PROVIDER
                    : LocationManager.GPS_PROVIDER;
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        } finally {
            try { lm.removeUpdates(listener); } catch (Throwable ignored) {}
        }
        return holder[0];
    }

    /** 反向地理编码：坐标 -> 地址。失败返回 null。 */
    private static String reverseGeocode(double lat, double lon) {
        try {
            Geocoder geocoder;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                geocoder = new Geocoder(appContext, Locale.getDefault());
            } else {
                geocoder = new Geocoder(appContext);
            }
            List<Address> list = geocoder.getFromLocation(lat, lon, 1);
            if (list == null || list.isEmpty()) return null;
            Address a = list.get(0);
            StringBuilder sb = new StringBuilder();
            if (a.getAdminArea() != null) sb.append(a.getAdminArea());
            if (a.getLocality() != null) sb.append(a.getLocality());
            if (a.getSubLocality() != null) sb.append(a.getSubLocality());
            if (a.getThoroughfare() != null) sb.append(a.getThoroughfare());
            return sb.toString().trim();
        } catch (Throwable t) {
            // 反向地理编码依赖网络，失败不影响返回坐标
            return null;
        }
    }
}
