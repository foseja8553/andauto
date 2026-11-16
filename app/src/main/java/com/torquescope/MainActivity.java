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
import java.util.regex.*;

public class MainActivity extends Activity {
  static { System.loadLibrary("pitwall_telemetry"); }
  private static native int decodeObd(String response, int pid);
  BluetoothAdapter adapter; BluetoothSocket socket; volatile boolean running, demo; Thread worker;
  volatile int rpm=-1, speed=-1, pedal=-1; volatile String pedalLabel="가속 페달", status="OBD 연결 대기", rate="—"; volatile boolean connected;
  Dashboard dashboard; TextView state; Button connect, simulate; long lastSample;
  final UUID spp=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
  @Override public void onCreate(Bundle b) { super.onCreate(b); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); getWindow().getDecorView().setSystemUiVisibility(5894|1024|512); adapter=BluetoothAdapter.getDefaultAdapter();
    LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setBackgroundColor(Color.rgb(10,12,15)); dashboard=new Dashboard(); root.addView(dashboard,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(22,4,22,8); state=new TextView(this); state.setTextColor(0xffa2a7ad); state.setTextSize(12); bar.addView(state,new LinearLayout.LayoutParams(0,-2,1)); connect=new Button(this); connect.setText("OBD 연결"); bar.addView(connect); simulate=new Button(this); simulate.setText("데모 보기"); bar.addView(simulate); root.addView(bar); setContentView(root);
    connect.setOnClickListener(v->chooseDevice()); simulate.setOnClickListener(v->{if(demo){stop();}else startDemo();}); tick.run(); }
  Runnable tick=new Runnable(){public void run(){state.setText(status+"   /   "+rate); simulate.setText(demo?"데모 종료":"데모 보기"); dashboard.invalidate(); dashboard.postDelayed(this,100);}};
  void chooseDevice(){ if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},10);return;} if(adapter==null){status="이 기기에는 블루투스가 없습니다";return;} try {if(!adapter.isEnabled()){status="블루투스를 켠 뒤 다시 연결하세요";return;} Set<BluetoothDevice> devices=adapter.getBondedDevices(); if(devices.isEmpty()){status="설정에서 OBD 동글을 먼저 페어링하세요";return;} BluetoothDevice[] list=devices.toArray(new BluetoothDevice[0]); String[] names=new String[list.length]; for(int i=0;i<list.length;i++)names[i]=(list[i].getName()==null?"이름 없음":list[i].getName())+"  /  "+list[i].getAddress(); new AlertDialog.Builder(this).setTitle("페어링된 OBD 동글 선택").setItems(names,(d,n)->startObd(list[n])).setNegativeButton("취소",null).show(); }catch(SecurityException e){status="블루투스 권한이 필요합니다";} }
  void stop(){running=false; demo=false; connected=false; try{if(socket!=null)socket.close();}catch(Exception ignored){} socket=null; if(worker!=null && worker!=Thread.currentThread()){try{worker.join(500);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}} rpm=speed=pedal=-1; rate="—";status="OBD 연결 대기";}
  void startDemo(){stop(); demo=true; running=true; status="데모 데이터 · 실제 차량값 아님"; pedalLabel="가속 페달"; worker=new Thread(()->{long t=System.currentTimeMillis(); while(running&&demo){double s=(System.currentTimeMillis()-t)/1000.0; pedal=(int)(50+45*Math.sin(s*.8)); rpm=(int)(1500+pedal*35); speed=(int)(35+pedal*.7);rate="시뮬레이션"; try{Thread.sleep(100);}catch(Exception e){break;}}});worker.start();}
  void startObd(BluetoothDevice device){stop();running=true;status="동글 연결 중…";worker=new Thread(()->{try{BluetoothSocket local=device.createRfcommSocketToServiceRecord(spp);socket=local;local.connect();InputStream in=local.getInputStream();OutputStream out=local.getOutputStream();status="차량 프로토콜 확인 중…"; command(in,out,"ATZ",5000);command(in,out,"ATE0",2000);command(in,out,"ATL0",2000);command(in,out,"ATS0",2000);command(in,out,"ATH0",2000);command(in,out,"ATSP0",2000); command(in,out,"0100",12000); boolean usePedal=true; int failures=0, cycles=0;long start=System.currentTimeMillis(); connected=true;status="OBD 연결됨 · 표준 PID";
      while(running){ int value=readPid(in,out,usePedal?0x49:0x11,1); if(value>=0){pedal=value;pedalLabel=usePedal?"가속 페달":"스로틀 위치";failures=0;}else {pedal=-1;if(usePedal&&++failures>=2){usePedal=false;pedalLabel="스로틀 위치";}}
        int r=readPid(in,out,0x0c,2);rpm=r; int s=readPid(in,out,0x0d,1);speed=s; cycles++;long elapsed=System.currentTimeMillis()-start;if(elapsed>0)rate=String.format(java.util.Locale.US,"갱신 %.1f회/초",cycles*1000.0/elapsed);
      }
    }catch(Exception e){if(running)status="연결 실패 · 동글 호환성/페어링 확인";}finally{try{if(socket!=null)socket.close();}catch(Exception ignored){} connected=false;running=false;rpm=speed=pedal=-1;rate="—";}});worker.start(); }
  String command(InputStream in,OutputStream out,String cmd,int timeout)throws Exception{out.write((cmd+"\r").getBytes("US-ASCII"));out.flush();StringBuilder b=new StringBuilder();long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end&&running){while(in.available()>0){int c=in.read();if(c=='>')return b.toString();b.append((char)c);if(b.length()>4096)throw new IOException("response too long");}Thread.sleep(10);}throw new IOException("timeout");}
  int readPid(InputStream in,OutputStream out,int pid,int len)throws Exception {
    return decodeObd(command(in,out,String.format(java.util.Locale.US,"01%02X",pid),3000),pid);
  }
  @Override protected void onDestroy(){stop();super.onDestroy();}
  class Dashboard extends View {Paint p=new Paint(3); Canvas c; float w,h; Dashboard(){super(MainActivity.this);} void text(String s,float x,float y,float size,int color,boolean bold){p.setColor(color);p.setTextSize(size);p.setTypeface(bold?Typeface.create("sans-serif-condensed",Typeface.BOLD):Typeface.create("sans-serif",Typeface.NORMAL));c.drawText(s,x,y,p);} void rect(float x,float y,float a,float b,int color){p.setColor(color);c.drawRect(x,y,a,b,p);} String num(int n){return n<0?"—":String.valueOf(n);}
    protected void onDraw(Canvas canvas){c=canvas;w=getWidth();h=getHeight();float u=Math.min(w/900f,h/390f); c.save();c.translate((w-900*u)/2,(h-390*u)/2);c.scale(u,u);rect(0,0,900,390,0xff0a0c0f);rect(27,24,31,48,0xffe84539);text("PITWALL",42,45,25,0xfff1f2f2,true);text("TELEMETRY  /  01",750,42,13,0xff959ba2,true);rect(27,61,873,63,0xff343b41);
      text("VEHICLE SPEED",30,98,13,0xffa2a9b0,true);text(num(speed),26,192,100,0xfff3f4f3,true);text("KM/H",31,219,14,0xff939ca4,true);rect(301,86,303,224,0xff343b41);text("ENGINE",330,98,13,0xffa2a9b0,true);text(num(rpm),326,192,77,0xfff3f4f3,true);text("RPM",331,219,14,0xff939ca4,true);
      text("INPUT",30,263,13,0xffa2a9b0,true);text(pedalLabel,115,263,15,0xffe4e6e7,true);text(pedal<0?"—":pedal+"%",730,264,22,0xfff2f2f1,true);rect(30,278,870,299,0xff262c30); if(pedal>=0)rect(30,278,30+840*Math.max(0,Math.min(100,pedal))/100f,299,0xff47c6a2);for(int i=1;i<20;i++)rect(30+i*42,278,31+i*42,299,0xff0a0c0f);
      text("BRAKE",30,338,14,0xff919ba2,true);text("차량 신호 확인 필요",115,338,14,0xff9aa2a8,false);rect(30,352,870,359,0xff262c30);c.restore(); }
  }
}
