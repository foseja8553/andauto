package com.torquescope;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
  static { System.loadLibrary("pitwall_telemetry"); }
  private static native int decodeObd(String response, int pid);
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
  private BluetoothAdapter adapter;
  private Session active;
  private boolean demo;
  private int rpm=-1, speed=-1, pedal=-1;
  private String pedalLabel="가속 페달", status="OBD 연결 대기", rate="—";
  private Dashboard dashboard;
  private TextView state;
  private Button simulate;
  private long demoStart;
  private static final int BG=0xff0c0c0d, INK=0xfff4f2ec, DIM=0xff85837e, FAINT=0xff5e5c58, TRACK=0xff222224, AMBER=0xfff2a900, GREEN=0xff35c26b;
  private static final UUID SPP=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);
    adapter=BluetoothAdapter.getDefaultAdapter();
    LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setBackgroundColor(BG);
    dashboard=new Dashboard(); root.addView(dashboard,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(20),dp(2),dp(20),dp(10));
    state=new TextView(this); state.setTextColor(0xffa3a09a); state.setTextSize(13);
    bar.addView(state,new LinearLayout.LayoutParams(0,-2,1));
    Button connect=button("OBD 연결",true); bar.addView(connect);
    simulate=button("데모 보기",false);
    LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-2,-2); gap.leftMargin=dp(8); bar.addView(simulate,gap);
    root.addView(bar); setContentView(root);
    connect.setOnClickListener(v->chooseDevice());
    simulate.setOnClickListener(v->{if(demo) stop(); else startDemo();});
    render();
  }
  private int dp(float v) {return Math.round(v*getResources().getDisplayMetrics().density);}
  private Button button(String label,boolean filled) {
    Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14);
    b.setTextColor(filled?BG:INK); b.setStateListAnimator(null);
    b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setPadding(dp(20),0,dp(20),0);
    GradientDrawable shape=new GradientDrawable(); shape.setCornerRadius(dp(6));
    if(filled) shape.setColor(INK); else {shape.setColor(BG); shape.setStroke(dp(1),0xff55534f);}
    b.setBackground(new RippleDrawable(ColorStateList.valueOf(filled?0x33000000:0x33ffffff),shape,null));
    return b;
  }
  private void render() {
    String message=rate.equals("—")||rate.equals("시뮬레이션")?status:status+" · "+rate;
    if(!message.contentEquals(state.getText())) state.setText(message);
    String label=demo?"데모 종료":"데모 보기";
    if(!label.contentEquals(simulate.getText())) simulate.setText(label);
    dashboard.postInvalidateOnAnimation();
  }
  private void chooseDevice() {
    if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},10); return;
    }
    if(adapter==null) {status="이 기기에는 블루투스가 없습니다"; render(); return;}
    try {
      if(!adapter.isEnabled()) {status="블루투스를 켠 뒤 다시 연결하세요"; render(); return;}
      BluetoothDevice[] devices=adapter.getBondedDevices().toArray(new BluetoothDevice[0]);
      if(devices.length==0) {status="설정에서 OBD 동글을 먼저 페어링하세요"; render(); return;}
      String[] names=new String[devices.length];
      for(int i=0;i<devices.length;i++) names[i]=(devices[i].getName()==null?"이름 없음":devices[i].getName())+" / "+devices[i].getAddress();
      new AlertDialog.Builder(this).setTitle("페어링된 OBD 동글 선택").setItems(names,(d,n)->startObd(devices[n])).setNegativeButton("취소",null).show();
    } catch(SecurityException e) {status="블루투스 권한이 필요합니다"; render();}
  }
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
    super.onRequestPermissionsResult(request,permissions,results);
    if(request==10) {
      if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) chooseDevice();
      else {status="주변 기기 권한을 허용해야 연결할 수 있습니다"; render();}
    }
  }
  private void stop() {
    Session old=active; active=null;
    if(old!=null) old.close();
    demo=false; ui.removeCallbacks(demoFrame);
    rpm=speed=pedal=-1; rate="—"; status="OBD 연결 대기"; render();
  }
  private final Runnable demoFrame=new Runnable() { public void run() {
    if(!demo) return;
    double seconds=(SystemClock.elapsedRealtime()-demoStart)/1000.0;
    pedal=(int)(50+45*Math.sin(seconds*.8)); rpm=1500+pedal*35; speed=(int)(35+pedal*.7);
    render(); ui.postDelayed(this,33);
  }};
  private void startDemo() {
    stop(); demo=true; demoStart=SystemClock.elapsedRealtime();
    status="데모 데이터 · 실제 차량값 아님"; rate="시뮬레이션"; pedalLabel="가속 페달"; demoFrame.run();
  }
  private void publish(Session session,Runnable update) {
    ui.post(()->{if(active==session) {update.run(); render();}});
  }
  private void startObd(BluetoothDevice device) {
    stop(); status="동글 연결 중…"; render();
    try {
      Session session=new Session(device.createRfcommSocketToServiceRecord(SPP)); active=session;
      new Thread(()->poll(session),"pitwall-obd").start();
    } catch(IOException|SecurityException e) {status="연결 실패 · 권한/페어링 확인"; render();}
  }
  private void poll(Session s) {
    try {
      s.connect();
      publish(s,()->status="차량 프로토콜 확인 중…");
      s.command("ATZ",5000);
      for(String cmd:new String[]{"ATE0","ATL0","ATS0","ATH0","ATSP0","ATAT1"}) s.command(cmd,2000);
      s.command("0100",12000);
      publish(s,()->status="OBD 연결됨 · 표준 PID");
      boolean usePedal=true; int failures=0, samples=0;
      long nextRpm=0,nextSpeed=0,window=SystemClock.elapsedRealtime();
      while(!s.closed) {
        long started=SystemClock.elapsedRealtime();
        int value=decodeObd(s.command(usePedal?"0149":"0111",3000),usePedal?0x49:0x11);
        String label=usePedal?"가속 페달":"스로틀 위치";
        publish(s,()->{pedal=value; pedalLabel=label;});
        if(value>=0) failures=0;
        else if(usePedal && ++failures>=2) usePedal=false;
        long now=SystemClock.elapsedRealtime(); samples++;
        if(now-window>=1000) {
          String measured=String.format(Locale.US,"입력 %.1f Hz · 응답 %d ms",samples*1000.0/(now-window),now-started);
          publish(s,()->rate=measured); samples=0; window=now;
        }
        // Request only one secondary value between pedal samples.
        // RPM <= 5 Hz, speed <= 2 Hz; pedal gets every remaining slot.
        if(now>=nextSpeed) {
          int v=decodeObd(s.command("010D",3000),0x0d);
          publish(s,()->speed=v); nextSpeed=SystemClock.elapsedRealtime()+500;
        } else if(now>=nextRpm) {
          int v=decodeObd(s.command("010C",3000),0x0c);
          publish(s,()->rpm=v); nextRpm=SystemClock.elapsedRealtime()+200;
        }
      }
    } catch(Exception e) {
      publish(s,()->{status="응답 없음/연결 종료 · 동글과 차량 전원을 확인하세요"; rpm=speed=pedal=-1; rate="—";});
    } finally {s.close();}
  }
  private final class Session {
    final BluetoothSocket socket;
    volatile boolean closed;
    ElmTransport transport;
    Session(BluetoothSocket socket) {this.socket=socket;}
    void close() {closed=true; try {socket.close();} catch(IOException ignored) {}}
    void connect() throws IOException {
      ScheduledFuture<?> timeout=deadlines.schedule(this::close,12,TimeUnit.SECONDS);
      try {socket.connect(); transport=new ElmTransport(socket.getInputStream(),socket.getOutputStream(),deadlines,this::close);}
      finally {timeout.cancel(false);}
    }
    String command(String cmd,int timeoutMs) throws IOException {
      if(closed) throw new IOException("Closed session");
      return transport.command(cmd,timeoutMs);
    }
  }

  @Override protected void onStop() {stop(); super.onStop();}
  @Override protected void onDestroy() {stop(); ui.removeCallbacksAndMessages(null); deadlines.shutdownNow(); super.onDestroy();}
  class Dashboard extends View {
    final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    final Typeface regular=Typeface.create("sans-serif",Typeface.NORMAL);
    final Typeface digits=Typeface.create("sans-serif-condensed",Typeface.BOLD);
    Canvas c;
    Dashboard() { super(MainActivity.this); }
    float text(String s,float x,float y,float size,int color,boolean bold,Paint.Align align) {
      p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTypeface(bold?digits:regular); p.setTextSize(size); p.setTextAlign(align);
      c.drawText(s,x,y,p); return p.measureText(s);
    }
    void rect(float x,float y,float width,float height,int color) {
      p.setStyle(Paint.Style.FILL); p.setColor(color); c.drawRect(x,y,x+width,y+height,p);
    }
    String num(int v) {return v<0?"—":String.valueOf(v);}
    @Override protected void onDraw(Canvas canvas) {
      c=canvas; float scale=Math.min(getWidth()/900f,getHeight()/390f);
      c.drawColor(BG);
      c.save(); c.translate((getWidth()-900*scale)/2,(getHeight()-390*scale)/2); c.scale(scale,scale);
      final Paint.Align L=Paint.Align.LEFT, C=Paint.Align.CENTER, R=Paint.Align.RIGHT;

      // 회전수 바: 1000 rpm마다 한 칸
      rect(36,34,624,20,TRACK);
      if(rpm>=0) rect(36,34,624*Math.min(8000,rpm)/8000f,20,AMBER);
      for(int i=0;i<=8;i++) {
        float x=36+624*i/8f;
        if(i>0 && i<8) rect(x-1,34,2,20,BG);
        text(String.valueOf(i),x,74,13,DIM,false,i==0?L:i==8?R:C);
      }

      // 속도와 회전수
      float w=text(num(speed),32,290,168,INK,true,L);
      text("km/h",32+w+12,290,22,DIM,false,L);
      text(num(rpm),610,290,60,INK,true,R);
      text("rpm",620,290,18,DIM,false,L);

      // 페달
      rect(712,62,68,268,TRACK);
      if(pedal>=0) {float h=268*Math.min(100,Math.max(0,pedal))/100f; rect(712,330-h,68,h,GREEN);}
      text(pedal<0?"—":pedal+"%",746,50,20,INK,true,C);
      text(pedalLabel,746,354,13,DIM,false,C);

      p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(0xff3a3a3c); c.drawRect(796.5f,62.5f,863.5f,329.5f,p);
      text("미지원",830,200,12,FAINT,false,C);
      text("—",830,50,20,FAINT,true,C);
      text("브레이크",830,354,13,DIM,false,C);
      c.restore();
    }
  }
}
