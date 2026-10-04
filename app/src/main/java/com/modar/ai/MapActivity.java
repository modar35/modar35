package com.modar.ai;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Карта заказов на OpenStreetMap. Навигация передаётся установленному картографическому приложению. */
public class MapActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_map);
        findViewById(R.id.map_back).setOnClickListener(v -> finish());
        WebView web=findViewById(R.id.map_web); WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setGeolocationEnabled(true); web.setWebViewClient(new WebViewClient()); web.setWebChromeClient(new WebChromeClient()); web.addJavascriptInterface(new Object(){@android.webkit.JavascriptInterface public void navigate(double lat,double lon,String label){runOnUiThread(()->{try{startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:"+lat+","+lon+"?q="+lat+","+lon+"("+Uri.encode(label)+")")));}catch(Exception e){}});}},"Android"); web.loadDataWithBaseURL("https://www.openstreetmap.org/", html(), "text/html", "UTF-8", null);
    }
    private String html(){return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/><script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script><style>html,body,#map{height:100%;margin:0}.go{background:#f28b52;color:white;border:0;border-radius:8px;padding:9px 12px}</style></head><body><div id='map'></div><script>let map=L.map('map').setView([55.75,37.62],10);L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{attribution:'OpenStreetMap'}).addTo(map);function go(a,b){Android.navigate(a,b,'Маршрут')}let points=[[55.891,37.445,'Химки → Мытищи · 1 800 ₽'],[55.742,37.615,'Центр → Подольск · 3 500 ₽'],[55.79,37.94,'Балашиха → Москва · 950 ₽']];points.forEach(p=>L.marker([p[0],p[1]]).addTo(map).bindPopup('<b>'+p[2]+'</b><br><br><button class=\"go\" onclick=\"go('+p[0]+','+p[1]+')\">Построить маршрут</button>'));</script></body></html>";}
}
