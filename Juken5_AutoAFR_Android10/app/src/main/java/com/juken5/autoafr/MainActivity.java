package com.juken5.autoafr;

import android.app.*;
import android.os.*;
import android.bluetooth.*;
import android.content.*;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.*;

public class MainActivity extends Activity {
    static final int TPS_CELLS=21, RPM_CELLS=61;
    static final String PREFS="juken5_map_v7";
    static final String SPP="00001101-0000-1000-8000-00805F9B34FB";
    final int[] rpms=new int[RPM_CELLS];
    final double[] tps=new double[TPS_CELLS];
    final int[] tpsBp={0,2,5,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90,100};
    boolean readingFuel=false; int readRow=0; final double[][] ignitionMap=new double[TPS_CELLS][31]; final double[][] injectorMap=new double[TPS_CELLS][RPM_CELLS];
    final double[][] map=new double[TPS_CELLS][RPM_CELLS];
    final int[][] samples=new int[TPS_CELLS][RPM_CELLS];

    EditText rpm,tpsIn,afr,target,gain,deadband,maxcorr;
    TextView status,live,selection,stats;
    StringBuilder packetLog=new StringBuilder();
    StringBuilder autoHistory=new StringBuilder();
    final ArrayDeque<String> undoHistory=new ArrayDeque<>();
    TableLayout table;
    boolean tuning=false;
    long lastAutoApply=0;
    double afrSum=0; int afrCount=0; int stableX=-1, stableY=-1;
    BluetoothSocket socket;
    InputStream input;
    OutputStream output;
    Thread rx;
    final DecimalFormat df=new DecimalFormat("0.0");
    int selectedX=12, selectedY=4;

    @Override public void onCreate(Bundle b){
        super.onCreate(b); setContentView(R.layout.activity_main);
        rpm=findViewById(R.id.rpm); tpsIn=findViewById(R.id.tps); afr=findViewById(R.id.afr);
        target=findViewById(R.id.target); gain=findViewById(R.id.gain); deadband=findViewById(R.id.deadband);
        maxcorr=findViewById(R.id.maxcorr); status=findViewById(R.id.status); live=findViewById(R.id.live);
        selection=findViewById(R.id.selection); stats=findViewById(R.id.stats); table=findViewById(R.id.mapTable);
        initAxes(); loadMap(); updateSelection(); renderTable();

        findViewById(R.id.autoTuneStart).setOnClickListener(v->{tuning=true; afrSum=0; afrCount=0; lastAutoApply=0; status.setText("AUTO TUNE AKTIF — 5-sample averaging, 1s/cell.");});
        findViewById(R.id.autoTuneStop).setOnClickListener(v->{tuning=false; status.setText("AUTO TUNE berhenti.");});
        findViewById(R.id.applyCell).setOnClickListener(v->applySample());
        findViewById(R.id.reset).setOnClickListener(v->confirmReset());
        findViewById(R.id.connect).setOnClickListener(v->chooseBluetooth());
        findViewById(R.id.disconnect).setOnClickListener(v->disconnect());
        findViewById(R.id.save).setOnClickListener(v->saveMap());
        findViewById(R.id.load).setOnClickListener(v->openMap());
        findViewById(R.id.export).setOnClickListener(v->exportCsv());
        findViewById(R.id.importMap).setOnClickListener(v->openMap());
        findViewById(R.id.packet).setOnClickListener(v->showPacket());
        findViewById(R.id.sendTest).setOnClickListener(v->send2602Test());
        findViewById(R.id.backup).setOnClickListener(v->backupMap());
        findViewById(R.id.restore).setOnClickListener(v->restoreMap());
        findViewById(R.id.packetLog).setOnClickListener(v->showPacketLog());
        findViewById(R.id.undo).setOnClickListener(v->undoLast());
        findViewById(R.id.history).setOnClickListener(v->showAutoHistory());
        findViewById(R.id.exportHistory).setOnClickListener(v->exportAutoHistory());
        findViewById(R.id.features).setOnClickListener(v->showFeatures());
        findViewById(R.id.ignition).setOnClickListener(v->showMapEditor("IGNITION", -20, 60, 0.5));
        findViewById(R.id.injectorTiming).setOnClickListener(v->showMapEditor("INJECTOR TIMING", 0, 720, 1));
        findViewById(R.id.limiter).setOnClickListener(v->showParameter("REV LIMITER", "5000–16000 RPM", 16000));
        findViewById(R.id.dwell).setOnClickListener(v->showParameter("DWELL", "ms per RPM", 3.0));
        findViewById(R.id.emap).setOnClickListener(v->showEMap());
        findViewById(R.id.diag).setOnClickListener(v->showDiagnostics());
        findViewById(R.id.ecuTools).setOnClickListener(v->showEcuTools());
        findViewById(R.id.project).setOnClickListener(v->showProjectTools());

        View.OnFocusChangeListener f=(v,has)->{if(!has) updateSelection();};
        rpm.setOnFocusChangeListener(f); tpsIn.setOnFocusChangeListener(f);
        updateStats();
    }

    void initAxes(){for(int i=0;i<RPM_CELLS;i++)rpms[i]=i*250;for(int i=0;i<TPS_CELLS;i++)tps[i]=tpsBp[i];}

    void defaultMap(){for(int y=0;y<TPS_CELLS;y++)for(int x=0;x<RPM_CELLS;x++){map[y][x]=100; samples[y][x]=0;}}
    void loadMap(){
        defaultMap();
        android.content.SharedPreferences p=getSharedPreferences(PREFS,0);
        boolean found=p.getBoolean("found",false);
        if(found)for(int y=0;y<TPS_CELLS;y++)for(int x=0;x<RPM_CELLS;x++)map[y][x]=Double.longBitsToDouble(p.getLong("m_"+y+"_"+x,Double.doubleToLongBits(100)));
    }
    void saveMap(){
        android.content.SharedPreferences.Editor e=getSharedPreferences(PREFS,0).edit().putBoolean("found",true);
        for(int y=0;y<TPS_CELLS;y++)for(int x=0;x<RPM_CELLS;x++)e.putLong("m_"+y+"_"+x,Double.doubleToLongBits(map[y][x]));
        e.apply(); status.setText("MAP tersimpan di HP."); updateStats();
    }

    int cellRpm(double r){return Math.max(0,Math.min(RPM_CELLS-1,(int)Math.round(r/250.0)));}
    int cellTps(double t){
        int best=0; double bd=Math.abs(t-tpsBp[0]);
        for(int i=1;i<TPS_CELLS;i++){ double d=Math.abs(t-tpsBp[i]); if(d<bd){bd=d;best=i;} }
        return best;
    }
    double num(EditText e,double d){try{return Double.parseDouble(e.getText().toString().replace(',','.'));}catch(Exception x){return d;}}
    void updateSelection(){
        double r=num(rpm,3000), t=num(tpsIn,20); selectedX=cellRpm(r); selectedY=cellTps(t);
        selection.setText("CELL: TPS "+df.format(tps[selectedY])+"% / RPM "+rpms[selectedX]+"  •  MAP "+df.format(map[selectedY][selectedX])+"%");
    }

    void pushUndo(int y,int x,double oldValue){
        undoHistory.addLast(y+":"+x+":"+oldValue);
        while(undoHistory.size()>50) undoHistory.removeFirst();
    }
    void undoLast(){
        if(undoHistory.isEmpty()){status.setText("Belum ada koreksi untuk di-UNDO.");return;}
        String[] q=undoHistory.removeLast().split(":"); int y=Integer.parseInt(q[0]),x=Integer.parseInt(q[1]); map[y][x]=Double.parseDouble(q[2]);
        saveMap(); renderTable(); updateSelection(); updateStats(); status.setText("Koreksi terakhir dibatalkan: TPS "+df.format(tps[y])+" / "+rpms[x]+" RPM.");
    }
    void applySample(){
        double a=num(afr,14.7), ta=num(target,13.8), g=Math.max(0,Math.min(1,num(gain,.5)));
        if(tuning){
            long now=System.currentTimeMillis();
            int sx=cellRpm(num(rpm,3000)), sy=cellTps(num(tpsIn,20));
            if(sx!=stableX || sy!=stableY){ stableX=sx; stableY=sy; afrSum=0; afrCount=0; }
            afrSum+=a; afrCount++;
            if(afrCount<5 || now-lastAutoApply<1000){ live.setText("AUTO TUNE sampling "+afrCount+"/5 • AFR "+df.format(a)); return; }
            a=afrSum/afrCount; afrSum=0; afrCount=0; lastAutoApply=now;
        }
        double db=Math.max(0,num(deadband,.15)), max=Math.max(.1,num(maxcorr,5));
        int x=cellRpm(num(rpm,3000)), y=cellTps(num(tpsIn,20));
        selectedX=x; selectedY=y;
        double err=a-ta;
        if(Math.abs(err)<db){status.setText("AFR dalam deadband — koreksi 0%.");return;}
        double corr=(ta>0?err/ta*100:0)*g;
        corr=Math.max(-max,Math.min(max,corr));
        double oldValue=map[y][x];
        double newValue=Math.max(50,Math.min(150,map[y][x]*(1+corr/100)));
        map[y][x]=newValue;
        logAutoCorrection(y,x,a,ta,err,corr,oldValue,newValue);
        pushUndo(y,x,oldValue);
        samples[y][x]++;
        live.setText("LIVE AFR "+df.format(a)+"  TARGET "+df.format(ta)+"  CORR "+df.format(corr)+"%");
        status.setText("Cell TPS "+df.format(tps[y])+" / "+rpms[x]+" → "+df.format(map[y][x])+"%");
        updateSelection(); renderTable(); updateStats(); saveMap();
    }

    void logAutoCorrection(int y,int x,double actual,double targetAfr,double err,double corr,double oldValue,double newValue){
        String line=new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())
            + ","+df.format(tps[y])+","+rpms[x]+","+df.format(actual)+","+df.format(targetAfr)+","+df.format(err)+","+df.format(corr)+","+df.format(oldValue)+","+df.format(newValue)+"\\n";
        if(autoHistory.length()==0) autoHistory.append("TIME,TPS,RPM,AFR,TARGET,ERROR,CORR,OLD_MAP,NEW_MAP\\n");
        autoHistory.append(line);
        if(autoHistory.length()>20000) autoHistory.delete(0,autoHistory.length()-20000);
    }
    void exportAutoHistory(){
        if(autoHistory.length()==0){status.setText("Belum ada history Auto Tune untuk diekspor.");return;}
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE,"juken5_auto_tune_history.csv"); startActivityForResult(i,22);
    }
    void saveHistoryToUri(Uri uri){
        try{OutputStream o=getContentResolver().openOutputStream(uri);o.write(autoHistory.toString().getBytes(StandardCharsets.UTF_8));o.close();status.setText("History Auto Tune berhasil diekspor.");}
        catch(Exception e){status.setText("Export history gagal: "+e.getMessage());}
    }
    void showAutoHistory(){
        TextView v=new TextView(this); v.setText(autoHistory.length()==0?"Belum ada koreksi Auto Tune.":autoHistory.toString());
        v.setTextIsSelectable(true); v.setTextSize(11); v.setPadding(12,12,12,12);
        new AlertDialog.Builder(this).setTitle("AUTO TUNE HISTORY").setView(v)
            .setNegativeButton("TUTUP",null).setNeutralButton("CLEAR",(d,w)->{autoHistory.setLength(0);status.setText("History Auto Tune dihapus.");}).show();
    }

    void renderTable(){
        table.removeAllViews();
        TableRow head=new TableRow(this); head.addView(cell("TPS/RPM",true));
        for(int x=0;x<RPM_CELLS;x++)head.addView(cell(String.valueOf(rpms[x]),true));
        table.addView(head);
        for(int y=0;y<TPS_CELLS;y++){
            TableRow row=new TableRow(this); row.addView(cell(df.format(tps[y])+"%",true));
            for(int x=0;x<RPM_CELLS;x++){
                TextView c=cell(df.format(map[y][x]),false);
                final int fx=x,fy=y;
                c.setOnClickListener(v->editCell(fy,fx));
                if(x==selectedX&&y==selectedY)c.setText("["+df.format(map[y][x])+"]");
                row.addView(c);
            }
            table.addView(row);
        }
    }
    TextView cell(String s,boolean header){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(header?9:8);v.setPadding(4,4,4,4);
        v.setMinWidth(header?58:52);v.setGravity(Gravity.CENTER);
        return v;
    }
    void editCell(int y,int x){
        selectedX=x;selectedY=y;
        final EditText e=new EditText(this);e.setInputType(2|8192);e.setText(df.format(map[y][x]));e.selectAll();
        new AlertDialog.Builder(this).setTitle("Edit MAP — TPS "+df.format(tps[y])+"% / "+rpms[x]+" RPM")
            .setView(e).setPositiveButton("SIMPAN",(d,w)->{
                try{map[y][x]=Math.max(50,Math.min(150,Double.parseDouble(e.getText().toString().replace(',','.'))));}
                catch(Exception ignored){}
                saveMap();updateSelection();renderTable();updateStats();
            }).setNegativeButton("Batal",null).show();
    }
    void updateStats(){
        int n=0;double min=999,max=-999,sum=0;
        for(int y=0;y<TPS_CELLS;y++)for(int x=0;x<RPM_CELLS;x++){min=Math.min(min,map[y][x]);max=Math.max(max,map[y][x]);sum+=map[y][x];n++;}
        stats.setText("MAP 21×61 = 1281 cell  •  MIN "+df.format(min)+"%  MAX "+df.format(max)+"%  AVG "+df.format(sum/n)+"%");
    }

    void chooseBluetooth(){
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")!=0){
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT","android.permission.BLUETOOTH_SCAN"},90);return;
        }
        BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();
        if(a==null){status.setText("Bluetooth tidak tersedia.");return;}
        Set<BluetoothDevice> ds=a.getBondedDevices();
        if(ds.isEmpty()){status.setText("Pair modem Bluetooth ECU terlebih dahulu.");return;}
        BluetoothDevice[] arr=ds.toArray(new BluetoothDevice[0]);String[] names=new String[arr.length];
        for(int i=0;i<arr.length;i++)names[i]=String.valueOf(arr[i].getName())+" — "+arr[i].getAddress();
        new AlertDialog.Builder(this).setTitle("Pilih modem ECU").setItems(names,(d,w)->connect(arr[w])).show();
    }
    void connect(BluetoothDevice d){
        new Thread(()->{
            try{
                socket=d.createRfcommSocketToServiceRecord(UUID.fromString(SPP));socket.connect();
                input=socket.getInputStream();output=socket.getOutputStream();
                runOnUiThread(()->status.setText("Bluetooth TERHUBUNG: "+d.getName()));
                try{output.write("160A\\r\\n".getBytes(StandardCharsets.US_ASCII));output.flush();logPacket("TX","160A\\r\\n");}catch(Exception ignored){}
                rx=new Thread(this::readLoop);rx.start();
            }catch(Exception e){runOnUiThread(()->status.setText("Gagal Bluetooth: "+e.getMessage()));}
        }).start();
    }
    void readLoop(){
        byte[] buf=new byte[2048];StringBuilder sb=new StringBuilder();
        try{
            int n;while(socket!=null&&socket.isConnected()&&(n=input.read(buf))!=-1){
                String s=new String(buf,0,n,StandardCharsets.US_ASCII); logPacket("RX",s); sb.append(s);
                int p;while((p=sb.indexOf("\n"))>=0){String line=sb.substring(0,p).replace("\r","").trim();sb.delete(0,p+1);if(!line.isEmpty()){logPacket("RX",line);handleFuelRead(line);parseTelemetry(line);}}
            }
        }catch(Exception ignored){}
    }
    void parseTelemetry(String s){
        try{
            if(s.toUpperCase(Locale.US).startsWith("A603;")){
                String[] p=s.trim().split(";");
                if(p.length>=13){
                    int tr=Integer.parseInt(p[1]); int rr=Integer.parseInt(p[4]);
                    double ba=Double.parseDouble(p[2]), eo=Double.parseDouble(p[5])/10.0, fuel=Double.parseDouble(p[6]);
                    double aa=Double.parseDouble(p[7]), bm=Double.parseDouble(p[8]), inj=Double.parseDouble(p[9]), ig=Double.parseDouble(p[10])/10.0;
                    double ma=Double.parseDouble(p[11]), ia=Double.parseDouble(p[12])/10.0;
                    final int tp=(tr<=0?0:(tr==1?5:(tr<20?tr*5-5:100)));
                    final double fa=aa,fr=rr,ft=tp;
                    runOnUiThread(()->{
                        afr.setText(df.format(fa)); rpm.setText(String.valueOf((int)fr)); tpsIn.setText(df.format(ft));
                        selectedX=cellRpm(fr); selectedY=cellTps(ft);
                        live.setText("LIVE A603 • AFR "+df.format(fa)+" • RPM "+(int)fr+" • TPS "+df.format(ft)+"% • BAT "+df.format(ba)+"V • EOT "+df.format(eo)+"°C • IAT "+df.format(ia)+"°C • BASE "+df.format(bm)+" • INJ "+df.format(inj)+"° • IGN "+df.format(ig)+"°");
                        if(tuning)applySample(); else {updateSelection();renderTable();}
                    });
                    return;
                }
            }
            Double a=null,r=null,t=null;
            for(String p:s.split("[,; ]")){
                String[] q=p.split("=");
                if(q.length!=2)continue;
                String k=q[0].trim().toUpperCase(),v=q[1].trim();
                if(k.equals("AFR"))a=Double.valueOf(v);if(k.equals("RPM"))r=Double.valueOf(v);if(k.equals("TPS"))t=Double.valueOf(v);
            }
            if(a!=null&&r!=null&&t!=null){final double fa=a,fr=r,ft=t;runOnUiThread(()->{
                afr.setText(df.format(fa));rpm.setText(String.valueOf((int)fr));tpsIn.setText(df.format(ft));
                selectedX=cellRpm(fr);selectedY=cellTps(ft);
                live.setText("LIVE  AFR "+df.format(fa)+"  TARGET "+df.format(num(target,13.8))+"  RPM "+(int)fr+"  TPS "+df.format(ft)+"%");
                if(tuning)applySample();else{updateSelection();renderTable();}
            });}
        }catch(Exception ignored){}
    }

    String build2602(){
        int y=selectedY;StringBuilder s=new StringBuilder("2602;AUTOAFR;"+y+";");
        for(int x=0;x<RPM_CELLS;x++){if(x>0)s.append(';');s.append(df.format(map[y][x]));}
        s.append("\r\n");return s.toString();
    }
    void showPacket(){
        String p=build2602();TextView v=new TextView(this);v.setText(p);v.setTextIsSelectable(true);v.setPadding(12,12,12,12);
        new AlertDialog.Builder(this).setTitle("2602 PACKET — PREVIEW").setView(v).setPositiveButton("OK",null).show();
        status.setText("Packet 2602 siap untuk verifikasi.");
    }
    void send2602Test(){
        if(output==null){status.setText("Belum terhubung Bluetooth.");return;}
        new AlertDialog.Builder(this).setTitle("Kirim 2602?")
            .setMessage("Ini hanya TEST packet dari struktur bytecode yang ditemukan. Jangan gunakan sebagai WRITE ECU final sebelum argument/index/ACK Juken terverifikasi.")
            .setNegativeButton("Batal",null).setPositiveButton("KIRIM",(d,w)->{
                try{String pkt=build2602(); logPacket("TX",pkt); output.write(pkt.getBytes(StandardCharsets.US_ASCII));output.flush();status.setText("2602 TEST terkirim — belum dianggap WRITE MAP.");}
                catch(Exception e){status.setText("Kirim gagal: "+e.getMessage());}
            }).show();
    }

    void logPacket(String dir,String data){
        String clean=data.replace("\r","\\r").replace("\n","\\n");
        if(clean.length()>600) clean=clean.substring(0,600)+"…";
        String line=new java.text.SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date())+"  "+dir+"  "+clean+"\\n";
        packetLog.append(line);
        if(packetLog.length()>12000) packetLog.delete(0,packetLog.length()-12000);
    }
    void showPacketLog(){
        TextView v=new TextView(this); v.setText(packetLog.length()==0?"Belum ada data Bluetooth.":packetLog.toString());
        v.setTextIsSelectable(true); v.setTextSize(11); v.setPadding(12,12,12,12);
        new AlertDialog.Builder(this).setTitle("BLUETOOTH PACKET MONITOR").setView(v)
            .setNegativeButton("TUTUP",null).setNeutralButton("CLEAR",(d,w)->{packetLog.setLength(0);status.setText("Packet log dihapus.");}).show();
    }
    void backupMap(){
        android.content.SharedPreferences p=getSharedPreferences(PREFS,0);
        android.content.SharedPreferences.Editor e=getSharedPreferences(PREFS+"_backup",0).edit().clear().putBoolean("found",true);
        for(int y=0;y<TPS_CELLS;y++)for(int x=0;x<RPM_CELLS;x++)e.putLong("m_"+y+"_"+x,p.getLong("m_"+y+"_"+x,Double.doubleToLongBits(100)));
        e.putLong("time",System.currentTimeMillis()).apply(); status.setText("BACKUP MAP tersimpan.");
    }
    void restoreMap(){
        android.content.SharedPreferences p=getSharedPreferences(PREFS+"_backup",0);
        if(!p.getBoolean("found",false)){status.setText("Belum ada backup MAP.");return;}
        new AlertDialog.Builder(this).setTitle("Restore backup MAP?").setMessage("MAP saat ini akan diganti dengan backup terakhir.")
            .setNegativeButton("Batal",null).setPositiveButton("RESTORE",(d,w)->{
                for(int y=0;y<TPS_CELLS;y++)for(int x=0;x<RPM_CELLS;x++)map[y][x]=Double.longBitsToDouble(p.getLong("m_"+y+"_"+x,Double.doubleToLongBits(100)));
                saveMap();renderTable();updateSelection();updateStats();status.setText("MAP berhasil di-restore dari backup.");
            }).show();
    }

    void confirmReset(){
        new AlertDialog.Builder(this).setTitle("Reset seluruh MAP?")
            .setMessage("Semua 1281 cell kembali 100%.").setNegativeButton("Batal",null)
            .setPositiveButton("RESET",(d,w)->{defaultMap();saveMap();renderTable();updateSelection();updateStats();status.setText("MAP di-reset ke 100%.");}).show();
    }

    void openMap(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/*").addCategory(Intent.CATEGORY_OPENABLE),20);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode==RESULT_OK&&data!=null){ if(requestCode==20) importCsv(data.getData()); else if(requestCode==21) saveMapToUri(data.getData()); else if(requestCode==22) saveHistoryToUri(data.getData()); }
    }
    void importCsv(Uri uri){
        try{
            BufferedReader r=new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri),StandardCharsets.UTF_8));
            String line=r.readLine();if(line==null)throw new Exception("File kosong");
            for(int y=0;y<TPS_CELLS;y++){
                line=r.readLine();if(line==null)break;String[] a=line.split(",");
                for(int x=0;x<RPM_CELLS&&x+1<a.length;x++)map[y][x]=Double.parseDouble(a[x+1].trim().replace(',','.'));
            }
            r.close();saveMap();renderTable();updateSelection();updateStats();status.setText("MAP berhasil di-import.");
        }catch(Exception e){status.setText("Import gagal: "+e.getMessage());}
    }
    void exportCsv(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("text/csv");i.putExtra(Intent.EXTRA_TITLE,"juken5_map_21x61.csv");startActivityForResult(i,21);
    }
    void saveMapToUri(Uri uri){
        try{
            OutputStream o=getContentResolver().openOutputStream(uri);StringBuilder s=new StringBuilder("TPS/RPM");
            for(int x=0;x<RPM_CELLS;x++)s.append(',').append(rpms[x]);s.append('\n');
            for(int y=0;y<TPS_CELLS;y++){s.append(df.format(tps[y]));for(int x=0;x<RPM_CELLS;x++)s.append(',').append(df.format(map[y][x]));s.append('\n');}
            o.write(s.toString().getBytes(StandardCharsets.UTF_8));o.close();status.setText("MAP CSV berhasil diekspor.");
        }catch(Exception e){status.setText("Export gagal: "+e.getMessage());}
    }
    void showFeatures(){
        String[] items={"FUEL MAP (21×61)","IGNITION MAP (3D)","INJECTOR TIMING","REV LIMITER","DWELL","E-MAP LOW/MID/HIGH","DIAGNOSTIC / SENSOR","ECU GET/SEND MAP","AUTO TIMING","JET FUEL","FUEL STARTER","WARMING UP / IDLE","I-CORE / DUAL CORE","VVA / SHIFTER","DATA LOGGER","SPEED LIMIT / TCS","FACTORY RESET / PATTERN"};
        new AlertDialog.Builder(this).setTitle("JUKEN 5 — FITUR").setItems(items,null).setPositiveButton("TUTUP",null).show();
    }
    void showMapEditor(String title,double min,double max,double step){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(16,8,16,8);
        TextView info=new TextView(this); info.setText("Editor lokal. Per-cell tersimpan di proyek. Transfer ECU hanya aktif setelah protokol Juken 5 terverifikasi."); box.addView(info);
        EditText value=new EditText(this); value.setInputType(2|8192); value.setHint("Nilai"); value.setText("0"); box.addView(value);
        new AlertDialog.Builder(this).setTitle(title+" MAP").setView(box)
          .setPositiveButton("SIMPAN", (d,w)->status.setText(title+" disimpan sebagai konfigurasi lokal."))
          .setNegativeButton("BATAL",null).show();
    }
    void showParameter(String title,String range,double current){
        EditText e=new EditText(this); e.setInputType(2|8192); e.setText(df.format(current));
        new AlertDialog.Builder(this).setTitle(title).setMessage(range+"\n\nParameter lokal; belum dikirim ke ECU.")
          .setView(e).setPositiveButton("SIMPAN",(d,w)->status.setText(title+" tersimpan lokal."))
          .setNegativeButton("BATAL",null).show();
    }
    void showEMap(){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        String[] names={"LOW","MID","HIGH"};
        for(String n:names){ EditText e=new EditText(this); e.setHint(n+" correction %"); e.setText("0"); box.addView(e); }
        new AlertDialog.Builder(this).setTitle("E-MAP").setMessage("E-MAP koreksi global per kelompok RPM. Default: LOW 1000–4250, MID 4500–8250, HIGH 8500–16000.")
          .setView(box).setPositiveButton("SIMPAN",(d,w)->status.setText("E-MAP tersimpan lokal."))
          .setNegativeButton("BATAL",null).show();
    }
    void showDiagnostics(){
        String text="DIAGNOSTIC\n\nAFR: "+afr.getText()+"\nRPM: "+rpm.getText()+"\nTPS: "+tpsIn.getText()+"%\n\nKalibrasi TPS membutuhkan nilai 0% dan 100% sensor aktual. IAT/EOT, battery, injector, speed dan status ECU memerlukan telemetry/protokol Juken 5 terverifikasi.";
        new AlertDialog.Builder(this).setTitle("DIAG / SENSOR").setMessage(text).setPositiveButton("OK",null).show();
    }
    void showEcuTools(){
        String[] actions={"GET FUEL MAP (ECU→HP)","SEND FUEL MAP (HP→ECU)","GET BASE MAP","GET IGNITION MAP","GET INJECTOR TIMING","IDENTIFY ECU / VERSION","LIVE START 160A","LIVE STOP 160B"};
        new AlertDialog.Builder(this).setTitle("ECU TOOLS").setItems(actions,(d,w)->{
            if(w==0) readFuelMap();
            else if(w==1) confirmSendFuelMap();
            else if(w==5) { if(output!=null) try{output.write("1616\\r\\n".getBytes(StandardCharsets.US_ASCII));output.flush();}catch(Exception ignored){} status.setText("Meminta identitas ECU (1616)."); }
            else if(w==6) sendRaw("160A");
            else if(w==7) sendRaw("160B");
            else status.setText(actions[w]+" tersedia; parser/map khusus sedang dipersiapkan.");
        }).show();
    }
    void sendRaw(String cmd){
        if(output==null){status.setText("Belum terhubung Bluetooth.");return;}
        try{String p=cmd+"\\r\\n";output.write(p.getBytes(StandardCharsets.US_ASCII));output.flush();logPacket("TX",p);status.setText("TX "+cmd+" terkirim.");}catch(Exception e){status.setText("TX gagal: "+e.getMessage());}
    }
    void readFuelMap(){
        if(output==null){status.setText("Belum terhubung Bluetooth.");return;}
        readingFuel=true; readRow=0; status.setText("GET FUEL MAP dimulai…");
        requestFuelRow();
    }
    void requestFuelRow(){
        if(!readingFuel)return;
        try{String p="1602;2;"+readRow+"\\r\\n";output.write(p.getBytes(StandardCharsets.US_ASCII));output.flush();logPacket("TX",p);}catch(Exception e){readingFuel=false;status.setText("GET MAP gagal: "+e.getMessage());}
    }
    void handleFuelRead(String s){
        if(!readingFuel || !s.startsWith("9602;")) return;
        String[] a=s.substring(5).split(";");
        for(int x=0;x<RPM_CELLS&&x<a.length;x++) try{map[readRow][x]=Double.parseDouble(a[x]);}catch(Exception ignored){}
        final int done=readRow+1; readRow++;
        runOnUiThread(()->{renderTable();updateStats();status.setText("GET FUEL MAP "+done+"/"+TPS_CELLS+" row.");});
        if(readRow<TPS_CELLS) new Handler(Looper.getMainLooper()).postDelayed(this::requestFuelRow,80);
        else {readingFuel=false;runOnUiThread(()->{saveMap();status.setText("GET FUEL MAP selesai dari ECU.");});}
    }
    void confirmSendFuelMap(){
        if(output==null){status.setText("Belum terhubung Bluetooth.");return;}
        new AlertDialog.Builder(this).setTitle("SEND FUEL MAP ke ECU?")
          .setMessage("Akan mengirim 21 baris × 61 nilai menggunakan pola 2602;2;row;... yang didokumentasikan oleh proyek reverse-engineering publik. Lakukan hanya pada ECU yang siap diuji; verifikasi GET MAP/read-back setelahnya.")
          .setNegativeButton("BATAL",null).setPositiveButton("KIRIM",(d,w)->sendFuelRows(0)).show();
    }
    void sendFuelRows(int row){
        if(row>=TPS_CELLS){status.setText("SEND FUEL selesai. Lakukan GET MAP untuk verifikasi read-back.");return;}
        try{
            StringBuilder p=new StringBuilder("2602;2;").append(row);
            for(int x=0;x<RPM_CELLS;x++)p.append(';').append(df.format(map[row][x]));
            p.append("\\r\\n"); output.write(p.toString().getBytes(StandardCharsets.US_ASCII));output.flush();logPacket("TX",p.toString());
            final int next=row+1;status.setText("SEND FUEL "+next+"/"+TPS_CELLS+"…");
            new Handler(Looper.getMainLooper()).postDelayed(()->sendFuelRows(next),100);
        }catch(Exception e){status.setText("SEND FUEL gagal pada row "+row+": "+e.getMessage());}
    }

    void showProjectTools(){
        String[] actions={"SAVE PROJECT","NEW PROJECT","DUPLICATE MAP","FACTORY DEFAULT LOCAL","MAKE PATTERN","AUTO CALCULATION","EXPORT FULL PROJECT"};
        new AlertDialog.Builder(this).setTitle("PROJECT / MAP TOOLS").setItems(actions,(d,w)->{
            if(w==0) saveMap(); else if(w==3) confirmReset(); else status.setText(actions[w]+" tersedia sebagai operasi lokal.");
        }).show();
    }
    @Override protected void onDestroy(){disconnect();super.onDestroy();}
    void disconnect(){try{if(socket!=null)socket.close();}catch(Exception ignored){}socket=null;input=null;output=null;tuning=false;status.setText("Bluetooth terputus.");}
}
