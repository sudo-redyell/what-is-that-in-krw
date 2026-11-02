package net.kyum.woori;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** 런처: 오버레이 권한 확인 후 플로팅 버튼 서비스 시작. */
public class MainActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "‘다른 앱 위에 표시’ 권한이 필요합니다", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
            return; // onPermissionsResult에서 재진입
        }
        Intent i = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        if (OverlayService.inst != null) Toast.makeText(this, "플로팅 버튼 실행 중", Toast.LENGTH_SHORT).show();
        else Toast.makeText(this, "플로팅 버튼을 불러왔습니다", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rc, perms, res);
        onCreate(null); // 권한 결과 후 재확인 (finish로 종료됨)
    }
}
