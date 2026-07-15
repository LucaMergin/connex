package si.deliva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS="deliva", KEY="groups";
    private final int BRAND=Color.rgb(79,70,229), DARK=Color.rgb(31,41,55), BG=Color.rgb(247,247,251);
    private final int MUTED=Color.rgb(107,114,128), GREEN=Color.rgb(5,150,105), RED=Color.rgb(220,38,38);
    private JSONArray groups=new JSONArray();
    private int openGroup=-1;

    @Override public void onCreate(Bundle b){ super.onCreate(b); load(); dashboard(); }

    private void load(){
        try{ groups=new JSONArray(getSharedPreferences(PREFS,MODE_PRIVATE).getString(KEY,"[]")); }
        catch(Exception e){ groups=new JSONArray(); }
        if(groups.length()==0){
            try{
                JSONObject g=new JSONObject(); g.put("name","Poletni izlet");
                JSONArray m=new JSONArray(); m.put("Ti"); m.put("Ana"); m.put("Miha"); g.put("members",m);
                JSONArray x=new JSONArray();
                x.put(exp("Večerja",86.40,0,System.currentTimeMillis()-86400000L));
                x.put(exp("Gorivo",54.00,1,System.currentTimeMillis()-172800000L));
                g.put("expenses",x); groups.put(g); save();
            }catch(Exception ignored){}
        }
    }
    private JSONObject exp(String title,double amount,int payer,long time)throws Exception{
        JSONObject e=new JSONObject(); e.put("title",title); e.put("amount",amount); e.put("payer",payer); e.put("time",time); return e;
    }
    private void save(){ getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(KEY,groups.toString()).apply(); }

    private void dashboard(){
        openGroup=-1; LinearLayout body=page();
        body.addView(hero("DELIVA","Skupni stroški brez zapletov","Pregledno vidiš, kdo je plačal in kdo komu dolguje."));
        Button add=primary("+ Nova skupina"); add.setOnClickListener(v->newGroup()); body.addView(add,margin());
        double total=0; int expenses=0;
        for(int i=0;i<groups.length();i++){ try{ total+=balances(groups.getJSONObject(i))[0]; expenses+=groups.getJSONObject(i).getJSONArray("expenses").length(); }catch(Exception ignored){} }
        LinearLayout stats=card(); stats.addView(big(total>=0?"Drugi ti dolgujejo":"Ti dolguješ",money(Math.abs(total)),total>=0?GREEN:RED));
        stats.addView(text(groups.length()+" skupin  •  "+expenses+" stroškov",14,MUTED,Typeface.NORMAL)); body.addView(stats,margin());
        body.addView(section("Tvoje skupine"));
        for(int i=0;i<groups.length();i++) body.addView(groupCard(i),marginSmall());
        if(groups.length()==0) body.addView(empty("Ni še skupin","Ustvari prvo skupino in dodaj skupne stroške."),margin());
        body.addView(footer()); setContentView(wrap(body));
    }

    private View groupCard(final int index){
        LinearLayout c=card();
        try{
            JSONObject g=groups.getJSONObject(index); JSONArray m=g.getJSONArray("members"), e=g.getJSONArray("expenses"); double b=balances(g)[0];
            c.addView(text(g.getString("name"),20,DARK,Typeface.BOLD));
            c.addView(text(m.length()+" članov  •  "+e.length()+" stroškov",13,MUTED,Typeface.NORMAL));
            TextView bal=text((b>=0?"Tebi pripada ":"Dolguješ ")+money(Math.abs(b)),15,b>=0?GREEN:RED,Typeface.BOLD); bal.setPadding(0,dp(10),0,dp(8)); c.addView(bal);
            LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            Button open=primary("Odpri"); open.setOnClickListener(v->group(index)); row.addView(open,new LinearLayout.LayoutParams(0,dp(48),1));
            Button del=secondary("Izbriši"); del.setOnClickListener(v->confirmDeleteGroup(index)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1); p.setMargins(dp(8),0,0,0); row.addView(del,p); c.addView(row);
        }catch(Exception ignored){}
        return c;
    }

    private void group(int index){
        openGroup=index; LinearLayout body=page();
        try{
            JSONObject g=groups.getJSONObject(index); JSONArray members=g.getJSONArray("members"), ex=g.getJSONArray("expenses");
            LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(16),dp(16),dp(16),dp(12));
            Button back=secondary("‹ Nazaj"); back.setOnClickListener(v->dashboard()); bar.addView(back,new LinearLayout.LayoutParams(dp(100),dp(46)));
            TextView title=text(g.getString("name"),23,DARK,Typeface.BOLD); title.setPadding(dp(12),0,0,0); bar.addView(title,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1)); body.addView(bar);
            LinearLayout actions=new LinearLayout(this); actions.setPadding(dp(16),0,dp(16),0);
            Button addExpense=primary("+ Strošek"); addExpense.setOnClickListener(v->newExpense(index)); actions.addView(addExpense,new LinearLayout.LayoutParams(0,dp(50),1));
            Button addMember=secondary("+ Član"); addMember.setOnClickListener(v->newMember(index)); LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(50),1); ap.setMargins(dp(8),0,0,0); actions.addView(addMember,ap); body.addView(actions);
            body.addView(section("Stanje članov"));
            LinearLayout bc=card(); double[] bs=balances(g);
            for(int i=0;i<members.length();i++){ LinearLayout r=line(); r.addView(text(members.getString(i),15,DARK,Typeface.BOLD),new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1)); double b=bs[i]; r.addView(text((b>=0?"+":"-")+money(Math.abs(b)),15,b>=0?GREEN:RED,Typeface.BOLD)); bc.addView(r); } body.addView(bc,margin());
            List<String> debts=settlements(members,bs); body.addView(section("Predlog poravnave")); LinearLayout sc=card();
            if(debts.isEmpty()) sc.addView(text("Vse je poravnano.",14,GREEN,Typeface.BOLD)); else for(String d:debts) sc.addView(text("• "+d,14,DARK,Typeface.NORMAL)); body.addView(sc,margin());
            body.addView(section("Stroški"));
            for(int i=ex.length()-1;i>=0;i--) body.addView(expenseCard(index,i,g),marginSmall());
            if(ex.length()==0) body.addView(empty("Brez stroškov","Dodaj prvi skupni strošek."),margin());
            body.addView(footer()); setContentView(wrap(body));
        }catch(Exception e){ toast("Skupine ni mogoče odpreti."); dashboard(); }
    }

    private View expenseCard(int groupIndex,int expenseIndex,JSONObject g){
        LinearLayout c=card();
        try{
            JSONObject e=g.getJSONArray("expenses").getJSONObject(expenseIndex); JSONArray m=g.getJSONArray("members"); int payer=e.getInt("payer");
            LinearLayout row=line();
            LinearLayout left=new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL); left.addView(text(e.getString("title"),16,DARK,Typeface.BOLD)); left.addView(text("Plačal/a: "+m.optString(payer,"Neznano")+"  •  "+date(e.getLong("time")),12,MUTED,Typeface.NORMAL)); row.addView(left,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
            row.addView(text(money(e.getDouble("amount")),17,DARK,Typeface.BOLD)); c.addView(row);
            Button del=secondary("Odstrani"); del.setOnClickListener(v->confirmDeleteExpense(groupIndex,expenseIndex)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(42)); p.setMargins(0,dp(8),0,0); c.addView(del,p);
        }catch(Exception ignored){}
        return c;
    }

    private double[] balances(JSONObject g)throws Exception{
        JSONArray m=g.getJSONArray("members"), ex=g.getJSONArray("expenses"); double[] b=new double[m.length()];
        for(int i=0;i<ex.length();i++){ JSONObject e=ex.getJSONObject(i); double a=e.getDouble("amount"), share=a/m.length(); int payer=e.getInt("payer"); if(payer>=0&&payer<m.length()) b[payer]+=a; for(int j=0;j<m.length();j++) b[j]-=share; }
        return b;
    }
    private List<String> settlements(JSONArray members,double[] b){
        List<Integer> debtors=new ArrayList<>(), creditors=new ArrayList<>(); double[] x=b.clone();
        for(int i=0;i<x.length;i++){ if(x[i]<-0.005) debtors.add(i); else if(x[i]>0.005) creditors.add(i); }
        List<String> out=new ArrayList<>(); int d=0,c=0;
        while(d<debtors.size()&&c<creditors.size()){
            int di=debtors.get(d),ci=creditors.get(c); double amount=Math.min(-x[di],x[ci]); out.add(members.optString(di)+" plača "+members.optString(ci)+" "+money(amount)); x[di]+=amount; x[ci]-=amount; if(Math.abs(x[di])<0.01)d++; if(Math.abs(x[ci])<0.01)c++;
        }
        return out;
    }

    private void newGroup(){
        LinearLayout f=form(); EditText name=input("Ime skupine"); EditText members=input("Člani, ločeni z vejico"); members.setHint("npr. Ana, Miha"); f.addView(label("Ime skupine")); f.addView(name,field()); f.addView(label("Drugi člani")); f.addView(members,field());
        new AlertDialog.Builder(this).setTitle("Nova skupina").setView(f).setNegativeButton("Prekliči",null).setPositiveButton("Ustvari",(d,w)->{
            try{ String n=name.getText().toString().trim(); if(n.isEmpty()){toast("Vnesi ime skupine.");return;} JSONObject g=new JSONObject(); g.put("name",n); JSONArray m=new JSONArray(); m.put("Ti"); for(String s:members.getText().toString().split(",")){ s=s.trim(); if(!s.isEmpty())m.put(s); } g.put("members",m); g.put("expenses",new JSONArray()); groups.put(g); save(); dashboard(); }catch(Exception e){toast("Skupine ni bilo mogoče ustvariti.");}
        }).show();
    }
    private void newMember(int gi){
        EditText name=input("Ime člana"); new AlertDialog.Builder(this).setTitle("Dodaj člana").setView(name).setNegativeButton("Prekliči",null).setPositiveButton("Dodaj",(d,w)->{
            try{ String n=name.getText().toString().trim(); if(n.isEmpty())return; groups.getJSONObject(gi).getJSONArray("members").put(n); save(); group(gi); }catch(Exception e){toast("Člana ni bilo mogoče dodati.");}
        }).show();
    }
    private void newExpense(int gi){
        try{
            JSONObject g=groups.getJSONObject(gi); JSONArray m=g.getJSONArray("members"); ArrayList<String> names=new ArrayList<>(); for(int i=0;i<m.length();i++)names.add(m.getString(i));
            LinearLayout f=form(); EditText title=input("npr. Večerja"); EditText amount=input("0,00"); amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL); Spinner payer=new Spinner(this); payer.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
            f.addView(label("Opis")); f.addView(title,field()); f.addView(label("Znesek (€)")); f.addView(amount,field()); f.addView(label("Kdo je plačal?")); f.addView(payer,field());
            new AlertDialog.Builder(this).setTitle("Nov strošek").setView(f).setNegativeButton("Prekliči",null).setPositiveButton("Shrani",(d,w)->{
                try{ String t=title.getText().toString().trim(); double a=Double.parseDouble(amount.getText().toString().replace(',','.')); if(t.isEmpty()||a<=0){toast("Preveri opis in znesek.");return;} g.getJSONArray("expenses").put(exp(t,a,payer.getSelectedItemPosition(),System.currentTimeMillis())); save(); group(gi); }catch(Exception e){toast("Znesek ni veljaven.");}
            }).show();
        }catch(Exception e){toast("Stroška ni mogoče dodati.");}
    }
    private void confirmDeleteGroup(int i){ new AlertDialog.Builder(this).setTitle("Izbrišem skupino?").setMessage("Izbrisani bodo tudi vsi njeni stroški.").setNegativeButton("Ne",null).setPositiveButton("Izbriši",(d,w)->{ groups=remove(groups,i); save(); dashboard(); }).show(); }
    private void confirmDeleteExpense(int gi,int ei){ new AlertDialog.Builder(this).setTitle("Odstranim strošek?").setNegativeButton("Ne",null).setPositiveButton("Odstrani",(d,w)->{ try{ JSONObject g=groups.getJSONObject(gi); g.put("expenses",remove(g.getJSONArray("expenses"),ei)); save(); group(gi); }catch(Exception ignored){} }).show(); }
    private JSONArray remove(JSONArray a,int index){ JSONArray n=new JSONArray(); for(int i=0;i<a.length();i++)if(i!=index)n.put(a.opt(i)); return n; }

    private LinearLayout page(){ LinearLayout p=new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setBackgroundColor(BG); p.setPadding(0,0,0,dp(24)); return p; }
    private ScrollView wrap(View v){ ScrollView s=new ScrollView(this); s.setFillViewport(true); s.setBackgroundColor(BG); s.addView(v); return s; }
    private View hero(String brand,String title,String sub){ LinearLayout h=new LinearLayout(this); h.setOrientation(LinearLayout.VERTICAL); h.setPadding(dp(20),dp(28),dp(20),dp(24)); h.setBackground(round(BRAND,0)); h.addView(text(brand,13,Color.rgb(199,210,254),Typeface.BOLD)); TextView t=text(title,28,Color.WHITE,Typeface.BOLD); t.setPadding(0,dp(6),0,dp(8)); h.addView(t); h.addView(text(sub,15,Color.rgb(224,231,255),Typeface.NORMAL)); return h; }
    private LinearLayout card(){ LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(16),dp(16),dp(16),dp(16)); c.setBackground(round(Color.WHITE,18)); c.setElevation(dp(2)); return c; }
    private LinearLayout line(){ LinearLayout r=new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0,dp(5),0,dp(5)); return r; }
    private LinearLayout empty(String t,String s){ LinearLayout c=card(); c.setGravity(Gravity.CENTER); TextView a=text(t,17,DARK,Typeface.BOLD); a.setGravity(Gravity.CENTER); c.addView(a); TextView b=text(s,13,MUTED,Typeface.NORMAL); b.setGravity(Gravity.CENTER); b.setPadding(0,dp(5),0,0); c.addView(b); return c; }
    private TextView section(String s){ TextView t=text(s,18,DARK,Typeface.BOLD); t.setPadding(dp(16),dp(22),dp(16),dp(6)); return t; }
    private TextView footer(){ TextView t=text("Deliva v0.1  •  Podatki ostanejo samo na tej napravi",12,MUTED,Typeface.NORMAL); t.setGravity(Gravity.CENTER); t.setPadding(dp(16),dp(28),dp(16),dp(10)); return t; }
    private View big(String label,String value,int color){ LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.addView(text(label,13,MUTED,Typeface.BOLD)); TextView v=text(value,28,color,Typeface.BOLD); v.setPadding(0,dp(3),0,dp(8)); c.addView(v); return c; }
    private TextView label(String s){ TextView t=text(s,12,DARK,Typeface.BOLD); t.setPadding(0,dp(10),0,dp(4)); return t; }
    private LinearLayout form(){ LinearLayout f=new LinearLayout(this); f.setOrientation(LinearLayout.VERTICAL); f.setPadding(dp(22),dp(4),dp(22),dp(12)); return f; }
    private EditText input(String hint){ EditText e=new EditText(this); e.setHint(hint); e.setSingleLine(true); e.setTextSize(15); e.setPadding(dp(12),0,dp(12),0); e.setBackground(round(Color.rgb(245,247,250),12)); return e; }
    private Button primary(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD); b.setBackground(round(BRAND,14)); return b; }
    private Button secondary(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(BRAND); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD); b.setBackground(round(Color.rgb(238,242,255),14)); return b; }
    private TextView text(String s,int size,int color,int style){ TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setTypeface(Typeface.DEFAULT,style); t.setIncludeFontPadding(false); return t; }
    private GradientDrawable round(int color,int radius){ GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g; }
    private LinearLayout.LayoutParams margin(){ LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); p.setMargins(dp(16),dp(14),dp(16),0); return p; }
    private LinearLayout.LayoutParams marginSmall(){ LinearLayout.LayoutParams p=margin(); p.topMargin=dp(9); return p; }
    private LinearLayout.LayoutParams field(){ return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(50)); }
    private int dp(int n){ return Math.round(n*getResources().getDisplayMetrics().density); }
    private String money(double v){ NumberFormat f=NumberFormat.getCurrencyInstance(new Locale("sl","SI")); return f.format(v); }
    private String date(long t){ return new SimpleDateFormat("dd.MM.yyyy",new Locale("sl","SI")).format(new Date(t)); }
    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }
    @Override public void onBackPressed(){ if(openGroup>=0)dashboard(); else super.onBackPressed(); }
}
