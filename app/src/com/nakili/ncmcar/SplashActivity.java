package com.nakili.ncmcar;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

/**
 * 启动页：解析服务端地址（自愈），然后进主界面。
 *
 * 车机开机自启会直接进这里。地址解析可能要试几个候选，
 * 所以显示进度，避免用户对着黑屏等。
 */
public class SplashActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_splash);

        LocalCache.init(this);
        status = (TextView) findViewById(R.id.splash_status);
        status.setText("正在连接服务端…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String resolved = ServerResolver.resolve(SplashActivity.this, 4000);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        Intent i = new Intent(SplashActivity.this, MainActivity.class);
                        if (resolved == null) {
                            // 连不上也进主界面（离线模式），让用户能在设置里改地址
                            i.putExtra("offline", true);
                        }
                        startActivity(i);
                        finish();
                    }
                });
            }
        }).start();
    }
}
