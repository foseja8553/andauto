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
  private static final int BG=0xff101416, INK=0xfff4f2ec, DIM=0xff9caaa9, FAINT=0xff85928f, TRACK=0xff293136, LIME=0xffd0ed54, GREEN=0xff6ed6aa;
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
    dashboard.resetBars(); rpm=speed=pedal=-1; rate="—"; status="OBD 연결 대기"; render();
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
      PollSchedule schedule=new PollSchedule();
      long window=SystemClock.elapsedRealtime();
      while(!s.closed) {
        long started=SystemClock.elapsedRealtime();
        int value=decodeObd(s.command(usePedal?"0149":"0111",3000),usePedal?0x49:0x11);
        String label=usePedal?"가속 페달":"스로틀 위치";
        publish(s,()->{pedal=value; pedalLabel=label;});
        if(value>=0) failures=0;
        else if(usePedal && ++failures>=2) usePedal=false;
        long now=SystemClock.elapsedRealtime();
        if(value>=0) samples++;
        if(now-window>=1000) {
          String measured=String.format(Locale.US,"입력 %.1f Hz · 응답 %d ms",samples*1000.0/(now-window),now-started);
          publish(s,()->rate=measured); samples=0; window=now;
        }
        // Request only one secondary value between pedal samples.
        // RPM <= 5 Hz, speed <= 2 Hz; pedal gets every remaining slot.
        int secondary=schedule.next(now);
        if(secondary==0x0d) {
          int v=decodeObd(s.command("010D",3000),0x0d);
          publish(s,()->speed=v); schedule.completed(secondary,SystemClock.elapsedRealtime());
        } else if(secondary==0x0c) {
          int v=decodeObd(s.command("010C",3000),0x0c);
          publish(s,()->rpm=v); schedule.completed(secondary,SystemClock.elapsedRealtime());
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
    float text(String s,float x,float y,float size,int color,boolean bold,Paint.Align align) {
      p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTypeface(bold?digits:regular); p.setTextSize(size); p.setTextAlign(align);
      c.drawText(s,x,y,p); return p.measureText(s);
    }
    void rect(float x,float y,float width,float height,int color) {
      p.setStyle(Paint.Style.FILL); p.setColor(color); c.drawRect(x,y,x+width,y+height,p);
    }
    float rpmWidth=-1, pedalHeight=-1;
    long lastFrame;
    void resetBars() {speedText.valid=rpmText.valid=pedalText.valid=false; rpmWidth=pedalHeight=-1; lastFrame=0;}
    float approach(float current,float target,float weight) {
      if(target<0 || current<0 || Math.abs(target-current)<0.35f) return target;
      return current+(target-current)*weight;
    }
    void bar(float x,float y,float w,float h,int color) {
      if(w<=0 || h<=0) return;
      p.setStyle(Paint.Style.FILL); p.setColor(color);
      c.drawRoundRect(x,y,x+w,y+h,2,2,p);
    }
    final Readout speedText=new Readout(3), rpmText=new Readout(5), pedalText=new Readout(3);

    // Each column follows the latest sample independently, including leading spaces.
    class Readout {
      final int[] glyph, previous;
      final long[] changed;
      final String[] characters={"0","1","2","3","4","5","6","7","8","9"};
      boolean valid;

      Readout(int columns) {
        glyph=new int[columns];
        previous=new int[columns];
        changed=new long[columns];
      }

      boolean draw(int value,float edge,float baseline,float size,float pitch,long now) {
        if(value<0) {
          valid=false;
          text("—",edge,baseline,size,INK,true,Paint.Align.RIGHT);
          return false;
        }
        boolean moving=false;
        int remaining=value;
        for(int column=0;column<glyph.length;column++) {
          int next=column==0 || remaining>0 ? remaining%10 : -1;
          remaining/=10;
          if(!valid || glyph[column]!=next) {
            previous[column]=valid?glyph[column]:-1;
            glyph[column]=next;
            changed[column]=valid?now:now-90;
          }
          float progress=Math.min(1,(now-changed[column])/90f);
          float fade=(1-progress)*(1-progress);
          float x=edge-column*pitch;
          if(fade>0 && previous[column]>=0) {
            int oldColor=Color.argb(Math.round(100*fade),244,242,236);
            text(characters[previous[column]],x,baseline-size*.025f*(1-fade),size,oldColor,true,Paint.Align.RIGHT);
          }
          if(next>=0) {
            int newColor=Color.argb(255-Math.round(100*fade),244,242,236);
            text(characters[next],x,baseline+size*.025f*fade,size,newColor,true,Paint.Align.RIGHT);
          }
          moving |= progress<1;
        }
        valid=true;
        return moving;
      }
    }
    @Override protected void onDraw(Canvas canvas) {
      c=canvas; float scale=Math.min(getWidth()/900f,getHeight()/390f);
      c.drawColor(BG);
      c.save(); c.translate((getWidth()-900*scale)/2,(getHeight()-390*scale)/2); c.scale(scale,scale);
      final Paint.Align L=Paint.Align.LEFT, C=Paint.Align.CENTER, R=Paint.Align.RIGHT;

      long now=SystemClock.uptimeMillis();
      float weight=(float)-Math.expm1(-Math.min(32,Math.max(1,now-lastFrame))/22.0);
      lastFrame=now;
      float rpmTarget=rpm<0?-1:624*Math.min(8000,rpm)/8000f;
      float pedalTarget=pedal<0?-1:268*Math.min(100,pedal)/100f;
      rpmWidth=approach(rpmWidth,rpmTarget,weight);
      pedalHeight=approach(pedalHeight,pedalTarget,weight);
      rect(690,0,210,390,0xff171d20);
      rect(690,0,1,390,TRACK);
      c.save(); c.skew(-0.30f,0);
      rect(46,24,7,17,LIME); rect(56,24,3,17,LIME);
      c.restore();
      text("PITWALL",64,41,23,INK,true,L);
      text("TORRES   /   J150",660,39,14,DIM,false,R);
      rect(36,55,624,1,TRACK);
      text("ENGINE SPEED",36,84,13,DIM,false,L);
      text("× 1,000 rpm",660,84,13,DIM,false,R);

      c.save(); c.translate(42,98); c.skew(-0.34f,0);
      rect(0,0,618,18,TRACK);
      if(rpmWidth>0) rect(0,0,rpmWidth*618/624,18,LIME);
      c.restore();
      for(int i=0;i<=8;i++) {
        float x=36+624*i/8f;
        rect(Math.min(659,x),121,1,5,DIM);
        text(String.valueOf(i),x,144,14,DIM,false,i==0?L:i==8?R:C);
      }

      text("속도",36,193,15,DIM,false,L);
      boolean moving=speedText.draw(speed,305,319,148,86,now);
      text("km/h",321,319,20,DIM,false,L);
      rect(412,187,1,146,TRACK);
      text("회전수",442,193,15,DIM,false,L);
      moving |= rpmText.draw(rpm,656,291,56,33,now);
      text("rpm",656,319,18,DIM,false,R);

      text("페달 입력",714,40,16,INK,false,L);
      rect(714,55,162,1,TRACK);
      bar(726,83,38,268,TRACK);
      bar(726,351-pedalHeight,38,pedalHeight,GREEN);
      moving |= pedalText.draw(pedal,751,75,17,10,now);
      if(pedal>=0) text("%",754,75,13,DIM,false,L);
      text(pedalLabel,745,375,13,DIM,false,C);
      p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(0xff495456);
      c.drawRect(820.5f,83.5f,857.5f,350.5f,p);
      text("미지원",839,222,11,FAINT,false,C);
      text("—",839,75,17,FAINT,true,C);
      text("브레이크",839,375,13,DIM,false,C);
      c.restore();
      if(moving || rpmWidth!=rpmTarget || pedalHeight!=pedalTarget) postInvalidateOnAnimation();
    }
  }
}
