package com.torquescope;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.graphics.*;
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
  private static final UUID SPP=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);
    adapter=BluetoothAdapter.getDefaultAdapter();
    LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setBackgroundColor(0xff0a0c0f);
    dashboard=new Dashboard(); root.addView(dashboard,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(22,4,22,8);
    state=new TextView(this); state.setTextColor(0xffa2a7ad); state.setTextSize(12);
    bar.addView(state,new LinearLayout.LayoutParams(0,-2,1));
    Button connect=new Button(this); connect.setText("OBD 연결"); bar.addView(connect);
    simulate=new Button(this); simulate.setText("데모 보기"); bar.addView(simulate);
    root.addView(bar); setContentView(root);
    connect.setOnClickListener(v->chooseDevice());
    simulate.setOnClickListener(v->{if(demo) stop(); else startDemo();});
    render();
  }
  private void render() {
    String message=status+"   /   "+rate;
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
    final Typeface digits=Typeface.create("sans-serif-condensed",Typeface.BOLD_ITALIC);
    Canvas c;
    Dashboard() { super(MainActivity.this); }
    void text(String s,float x,float y,float size,int color,boolean bold) {
      p.setColor(color); p.setTypeface(bold?digits:regular); p.setTextSize(size); c.drawText(s,x,y,p);
    }
    void rect(float x,float y,float width,float height,int color) {
      p.setColor(color); c.drawRect(x,y,x+width,y+height,p);
    }
    String num(int v) {return v<0?"—":String.valueOf(v);}
    @Override protected void onDraw(Canvas canvas) {
      c=canvas; float scale=Math.min(getWidth()/900f,getHeight()/390f);
      c.save(); c.translate((getWidth()-900*scale)/2,(getHeight()-390*scale)/2); c.scale(scale,scale);
      c.drawColor(0xff101923);
      rect(32,28,3,20,0xff8bb5ca); text("PITWALL",46,45,20,0xffe7f0f6,true);
      text("TORRES  /  J150",175,44,12,0xff91a7b6,false);
      text(demo?"DEMO  ·  시뮬레이션":"OBD-II  ·  차량 데이터",690,44,12,0xff91a7b6,false);
      rect(32,64,836,1,0xff30404d);
      text("SPEED",35,104,12,0xff91a7b6,false);
      text(num(speed),27,222,112,0xfff1f6f9,true); text("km/h",245,221,19,0xffa5bbc9,false);
      rect(358,91,1,166,0xff30404d);
      text(pedalLabel.equals("스로틀 위치")?"THROTTLE":"ACCELERATOR",399,108,12,0xffb4c8d5,false);
      p.setTextAlign(Paint.Align.RIGHT); text(pedal<0?"—":pedal+"%",864,112,25,0xffe8f4f7,true); p.setTextAlign(Paint.Align.LEFT);
      rect(400,131,464,15,0xff263744);
      if(pedal>=0) rect(400,131,464*Math.min(100,Math.max(0,pedal))/100f,15,0xff81c6d8);
      text(pedalLabel,400,168,11,0xff7894a6,false);
      text("BRAKE",399,205,12,0xff8c9fab,false);
      p.setTextAlign(Paint.Align.RIGHT); text("—",864,209,25,0xff8195a2,true); p.setTextAlign(Paint.Align.LEFT);
      rect(400,228,464,4,0xff263744); text("차량 신호 확인 필요",400,253,11,0xff7894a6,false);
      rect(32,281,836,1,0xff30404d);
      text("ENGINE",34,311,11,0xff91a7b6,false);
      text(num(rpm),34,354,36,0xffe5eff6,true); text("rpm",147,353,13,0xff91a7b6,false);
      for(int i=0;i<32;i++) rect(252+i*19,324,15,13,rpm>=0 && i<Math.min(32,rpm/250f)?0xff87b5ce:0xff263744);
      text("0",252,356,10,0xff7894a6,false); text("4",552,356,10,0xff7894a6,false); text("8 × 1000",808,356,10,0xff7894a6,false);
      c.restore();
    }
  }
}
