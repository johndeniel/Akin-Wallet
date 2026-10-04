package com.akin.wallet.marketing;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.akin.wallet.AkinWallet;
import com.akin.wallet.FixtureWallet;
import com.akin.wallet.R;
import com.akin.wallet.activity.*;
import com.akin.wallet.security.AppLockManager;
import com.google.android.material.search.SearchView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.Assert.*;

/** Explicitly opt-in, invocation-isolated production-flow capture. Never disables FLAG_SECURE. */
public final class CommercialCaptureTest {
    private AkinWallet wallet;
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private File directory;
    private final JSONObject metadata = new JSONObject();
    private final JSONArray captures = new JSONArray(), proofs = new JSONArray();
    private final Application.ActivityLifecycleCallbacks tracker = new Application.ActivityLifecycleCallbacks() {
        public void onActivityResumed(Activity activity) { resumed = new WeakReference<>(activity); }
        public void onActivityCreated(Activity activity, Bundle state) { }
        public void onActivityStarted(Activity activity) { }
        public void onActivityPaused(Activity activity) { }
        public void onActivityStopped(Activity activity) { }
        public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        public void onActivityDestroyed(Activity activity) { }
    };
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private Activity activity() { Activity a = resumed.get(); assertNotNull(a); return a; }
    private void waitFor(Class<?> type, Predicate<Activity> ready) {
        for (int i=0;i<240;i++) {
            AtomicBoolean ok = new AtomicBoolean();
            main(() -> { Activity a=resumed.get(); if(a!=null && type.isInstance(a) && !a.isFinishing()) ok.set(ready.test(a)); });
            if(ok.get()) { SystemClock.sleep(140); return; }
            SystemClock.sleep(50);
        }
        throw new AssertionError("Capture state timed out: "+type.getSimpleName());
    }
    private void dashboard(int accounts) {
        waitFor(DashboardActivity.class, a -> {
            View content=a.findViewById(R.id.dashboard_content);
            RecyclerView rows=a.findViewById(R.id.dashboard_social_account_list);
            return content!=null && content.getVisibility()==View.VISIBLE && rows.getAdapter()!=null
                    && rows.getAdapter().getItemCount()==accounts && rows.getChildCount()>0;
        });
        SystemClock.sleep(500);
    }
    private void form(Class<?> type) {
        waitFor(type, a -> a.findViewById(R.id.form_primary_action)!=null
                && a.findViewById(R.id.form_primary_action).hasOnClickListeners());
    }
    private void click(int id) { main(() -> { View v=activity().findViewById(id); assertNotNull(v); assertTrue(v.performClick()); }); }
    private void row(int id,int position) { main(() -> {
        RecyclerView list=activity().findViewById(id); View v=list.getLayoutManager().findViewByPosition(position);
        assertNotNull(v); assertTrue(v.performClick());
    }); }
    private void back() { main(() -> ((AppCompatActivity) activity()).getOnBackPressedDispatcher().onBackPressed()); }
    private void worker(Runnable action) throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> error=new AtomicReference<>();
        wallet.getVaultStore().execute(() -> { try {action.run();} catch(Throwable t){error.set(t);} finally{done.countDown();} },
                t -> {error.set(t);done.countDown();});
        assertTrue(done.await(30,TimeUnit.SECONDS)); if(error.get()!=null) throw new AssertionError(error.get());
    }
    private void bottom(View view) {
        if(view instanceof NestedScrollView) ((NestedScrollView)view).fullScroll(View.FOCUS_DOWN);
        else if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup g=(android.view.ViewGroup)view;
            for(int i=0;i<g.getChildCount();i++) bottom(g.getChildAt(i));
        }
    }
    private AlertDialog dialog() {
        try {
            Field f=BaseVaultActivity.class.getDeclaredField("ownedDialogs"); f.setAccessible(true);
            for(Object item:(List<?>)f.get(activity())) if(((AlertDialog)item).isShowing()) return (AlertDialog)item;
        } catch(ReflectiveOperationException e){throw new AssertionError(e);} return null;
    }
    private void capture(String name) {
        main(() -> {
            Activity a=activity(); assertTrue(wallet instanceof FixtureWallet);
            assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
            View root=a.findViewById(android.R.id.content);
            Bitmap bitmap=Bitmap.createBitmap(root.getWidth()*2,root.getHeight()*2,Bitmap.Config.ARGB_8888);
            try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))) {
                Canvas canvas=new Canvas(bitmap); canvas.drawColor(0xff050b18); canvas.scale(2,2); root.draw(canvas);
                AlertDialog d=a instanceof BaseVaultActivity ? dialog():null;
                if(d!=null) {
                    canvas.drawColor(0x88000000); View decor=d.getWindow().getDecorView();
                    int[] rootAt=new int[2],dialogAt=new int[2]; root.getLocationOnScreen(rootAt);decor.getLocationOnScreen(dialogAt);
                    canvas.save();canvas.translate(dialogAt[0]-rootAt[0],dialogAt[1]-rootAt[1]);decor.draw(canvas);canvas.restore();
                }
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));
                captures.put(new JSONObject().put("file",name+".png").put("activity",a.getClass().getSimpleName())
                        .put("width",bitmap.getWidth()).put("height",bitmap.getHeight()).put("real_dialog",d!=null));
            } catch(Exception e){throw new AssertionError(e);} finally{bitmap.recycle();}
        });
    }
    @Test public void captureVerifiedCommercialWorkflow() throws Exception {
        org.junit.Assume.assumeTrue("Commercial capture is opt-in", "true".equals(InstrumentationRegistry.getArguments().getString("commercialCapture")));
        wallet=(AkinWallet)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assertTrue(wallet instanceof FixtureWallet); wallet.registerActivityLifecycleCallbacks(tracker);
        directory=new File(wallet.getCacheDir(),"commercial-demo");assertTrue(directory.isDirectory()||directory.mkdirs());
        CountDownLatch ready=new CountDownLatch(1); AtomicReference<Throwable> authenticationError=new AtomicReference<>();
        AppLockManager.execute(() -> {try{AppLockManager.setPin(wallet,"1234");}catch(Throwable t){authenticationError.set(t);}finally{ready.countDown();}});
        assertTrue(ready.await(30,TimeUnit.SECONDS));if(authenticationError.get()!=null)throw new AssertionError(authenticationError.get());
        main(wallet::onUnlocked);
        worker(() -> {CommercialDemoSeeder.reseed(wallet);CommercialDemoSeeder.reseed(wallet);});
        main(wallet::lockAndClear);
        try(ActivityScenario<LockActivity> screen=ActivityScenario.launch(LockActivity.class)) {
            waitFor(LockActivity.class,a -> a.findViewById(R.id.lock_keypad_digit_1).hasOnClickListeners());
            capture("lock");
            int[] keys={R.id.lock_keypad_digit_1,R.id.lock_keypad_digit_2,R.id.lock_keypad_digit_3,R.id.lock_keypad_digit_4};
            for(int i=0;i<keys.length;i++){click(keys[i]);if(i<3)capture("pin-"+(i+1));}
            dashboard(4);capture("dashboard");proofs.put("Real four-key PIN verification unlocked fixture and launched Dashboard");
            row(R.id.dashboard_government_id_carousel,0);form(GovernmentIDActivity.class);capture("passport");
            main(() -> {try {
                Field inputs=GovernmentIDActivity.class.getDeclaredField("textInputs");inputs.setAccessible(true);
                EditText input=(EditText)((Map<?,?>)inputs.get(activity())).get("issuing_authority");
                assertNotNull(input);input.setText("DEMO OFFICE LOCAL");
            }catch(Exception e){throw new AssertionError(e);}});
            capture("passport-updated");main(() -> bottom(activity().findViewById(android.R.id.content)));SystemClock.sleep(300);capture("passport-save");
            click(R.id.form_primary_action);dashboard(4);capture("identity-saved");
            worker(() -> assertEquals("DEMO OFFICE LOCAL",wallet.getDbHelper().getIdCardById(2).getFieldsRef().get("issuing_authority")));
            proofs.put("Production Passport validation and Update committed DEMO OFFICE LOCAL");
            final int[] travel={0}; main(() -> {RecyclerView cards=activity().findViewById(R.id.dashboard_bank_card_carousel);
                View second=cards.getLayoutManager().findViewByPosition(1);assertNotNull(second);
                travel[0]=Math.max(0,cards.getLayoutManager().getDecoratedRight(second)-cards.getWidth()+cards.getPaddingRight());});
            int previous=0;
            for(int f=0;f<24;f++){double p=f/23.;int offset=(int)Math.round(travel[0]*p*p*(3-2*p)); final int dx=offset-previous;
                main(() -> ((RecyclerView)activity().findViewById(R.id.dashboard_bank_card_carousel)).scrollBy(dx,0));
                capture(String.format(Locale.ROOT,"card-swipe-%02d",f));previous=offset;}
            capture("cards");proofs.put("Actual production card carousel scrollBy frames with masked fictional identifiers");
            row(R.id.dashboard_social_account_list,0);form(SocialAccountActivity.class);capture("social");back();dashboard(4);
            click(R.id.dashboard_search_button);SystemClock.sleep(600);
            for(String query:new String[]{"J","Ju","Jua","Juan"}){
                main(() -> {SearchView search=activity().findViewById(R.id.dashboard_search_view);search.getEditText().setText(query);
                    ((android.view.inputmethod.InputMethodManager)activity().getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
                            .hideSoftInputFromWindow(search.getEditText().getWindowToken(),0);});SystemClock.sleep(350);capture("search-"+query.toLowerCase(Locale.ROOT));
            }
            main(() -> {RecyclerView results=activity().findViewById(R.id.dashboard_search_social_account_list);assertEquals(4,results.getAdapter().getItemCount());});
            proofs.put("Real Juan filtering returned all four seeded social rows");
            main(() -> ((SearchView)activity().findViewById(R.id.dashboard_search_view)).hide());SystemClock.sleep(500);
            click(R.id.dashboard_quick_add_button);capture("fab-menu");
            click(R.id.dashboard_quick_add_option_social_account);form(SocialAccountActivity.class);capture("create-empty");
            main(() -> ((EditText)activity().findViewById(R.id.social_account_username_field)).setText("juan.extra@example.invalid"));
            capture("create");main(() -> bottom(activity().findViewById(android.R.id.content)));SystemClock.sleep(300);capture("create-save");
            click(R.id.form_primary_action);dashboard(5);capture("created");
            worker(() -> assertEquals(1,wallet.getDbHelper().getAllSocialAccounts().stream().filter(s -> "juan.extra@example.invalid".equals(s.getUsername())).count()));
            proofs.put("Real FAB menu/default Google/username-only Save created one persisted fictional social row");
            row(R.id.dashboard_social_account_list,0);form(SocialAccountActivity.class);main(() -> bottom(activity().findViewById(android.R.id.content)));SystemClock.sleep(300);
            capture("delete-record");click(R.id.form_destructive_action);SystemClock.sleep(300);capture("delete-confirmation");
            main(() -> {AlertDialog d=dialog();assertNotNull(d);d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
            dashboard(4);capture("deleted");click(R.id.dashboard_settings_button);waitFor(SettingsActivity.class,a -> a.findViewById(R.id.settings_trash_row)!=null);capture("settings-route");
            click(R.id.settings_trash_row);waitFor(TrashActivity.class,a -> {RecyclerView rows=a.findViewById(R.id.trash_grid);return rows.getAdapter()!=null&&rows.getAdapter().getItemCount()==2&&rows.getChildCount()>1;});
            capture("trash");row(R.id.trash_grid,1);capture("trash-selected");proofs.put("Real delete confirmation soft-deleted row; Dashboard → Settings → Trash; tile selection reveals Restore");
            click(R.id.trash_restore_selected_button);waitFor(TrashActivity.class,a -> a.findViewById(R.id.trash_empty_state).getVisibility()==View.VISIBLE);
            worker(() -> assertEquals(1,wallet.getDbHelper().getAllSocialAccounts().stream().filter(s -> "juan.extra@example.invalid".equals(s.getUsername())).count()));
            proofs.put("Production Restore transaction returned created row to active SQLite state");back();waitFor(SettingsActivity.class,a -> true);capture("settings");
            click(R.id.settings_change_pin_row);waitFor(LockActivity.class,a -> a.findViewById(R.id.lock_keypad_digit_1)!=null);capture("change-pin");back();waitFor(SettingsActivity.class,a -> true);back();dashboard(5);capture("dashboard-final");
            metadata.put("fictional_demo_only",true).put("production_data_accessed",false).put("production_validation_modified",false)
                    .put("secure_window_flag_preserved",true).put("seeded_twice_without_duplicates",true)
                    .put("capture_method","Actual attached application View.draw at 2x Canvas scale; actual dialog decor; no device screenshot")
                    .put("authentication","Real production PIN verification; no biometric simulation")
                    .put("profile","Juan Dela Cruz — fictional demo")
                    .put("captures",captures).put("interaction_proofs",proofs).put("schema_version",1)
                    .put("device",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL)
                    .put("density",wallet.getResources().getDisplayMetrics().density).put("font_scale",wallet.getResources().getConfiguration().fontScale);
            try(FileOutputStream out=new FileOutputStream(new File(directory,"capture-metadata.json"))){out.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));}
        } finally {wallet.unregisterActivityLifecycleCallbacks(tracker);}
    }
}
