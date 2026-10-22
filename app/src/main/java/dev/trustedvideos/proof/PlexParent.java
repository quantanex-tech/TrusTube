// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import dev.trustedvideos.proof.plex.PlexApi;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Parent-scoped setup. Owner authentication is temporary and never saved or used for media. */
final class PlexParent {
    private final Activity activity;
    private final ProofState state;
    private PlexApi api;
    private boolean jwt=true;
    private String setupClient;
    private final BooleanSupplier permitted;
    private volatile boolean closed;
    private AlertDialog dialog;
    private PlexApi.Pin pin;
    private String owner;
    PlexParent(Activity activity,ProofState state,BooleanSupplier permitted) {
        this.activity=activity; this.state=state; this.permitted=permitted;
        configureApi(true);
    }
    private boolean valid() { return !closed && !activity.isDestroyed() && permitted.getAsBoolean(); }
    synchronized void close() { closed=true; owner=null; pin=null; if(dialog!=null) dialog.dismiss(); }
    interface Work<T> { T run() throws Exception; }
    private <T> void work(String title,Work<T> work,java.util.function.Consumer<T> done,Runnable retry) {
        if(!valid()) return;
        if(dialog!=null) dialog.dismiss();
        dialog=new AlertDialog.Builder(activity).setTitle(title).setMessage("Connecting to Plex…").setCancelable(false).show();
        state.credentials.execute(()-> {
            try { T result=work.run(); activity.runOnUiThread(()-> { if(!valid()) return; dialog.dismiss(); done.accept(result); }); }
            catch(Exception error) { activity.runOnUiThread(()-> {
                if(!valid()) return; dialog.dismiss();
                AlertDialog.Builder failure=new AlertDialog.Builder(activity).setTitle("Could not connect to Plex")
                        .setMessage(error instanceof PlexApi.ConnectionFailure || error instanceof PlexApi.RequestFailure?error.getMessage():title+" failed ("+error.getClass().getSimpleName()+"). Retry this step or reconnect Plex.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Retry",(d,w)->retry.run());
                if(jwt && error instanceof PlexApi.RequestFailure && ((PlexApi.RequestFailure)error).status==401)
                    failure.setNeutralButton("Compatibility sign-in",(d,w)->{ owner=null; pin=null; newPin(false); });
                dialog=failure.show();
            }); }
        });
    }
    private void configureApi(boolean jwtMode) {
        jwt=jwtMode; setupClient=jwt?state.plex.store.jwtClientId():state.plex.store.compatibilityClientId();
        api=new PlexApi(setupClient); api.guard(s->{ if(!valid()) throw new java.io.IOException("Parent settings locked."); });
    }
    void connect() {
        if(!valid()) return;
        dialog=new AlertDialog.Builder(activity).setTitle("Connect Plex")
                .setMessage("Choose device-code sign-in. Compatibility sign-in can help if Plex cannot verify a child through the newer method. Both require a verified restricted child.")
                .setPositiveButton("Standard sign-in",(d,w)->newPin(true))
                .setNeutralButton("Compatibility sign-in",(d,w)->newPin(false)).setNegativeButton("Cancel",null).show();
    }
    private void newPin(boolean jwtMode) {
        configureApi(jwtMode);
        work("Connect Plex",()->api.createPin(jwt?state.plex.store.jwk():null),p->{ pin=p; showCode(); },()->newPin(jwtMode));
    }
    private void showCode() {
        if(!valid() || pin==null) return;
        dialog=new AlertDialog.Builder(activity).setTitle("Connect Plex")
                .setMessage("On another device, open plex.tv/link and enter:\n\n"+pin.code()+"\n\nThen check authorization here.")
                .setNegativeButton("Cancel",(d,w)->{pin=null; owner=null;})
                .setPositiveButton("Check authorization",(d,w)->checkCode()).show();
    }
    private void checkCode() {
        final PlexApi.Pin p=pin; if(p==null) return;
        work("Check Plex authorization",()->api.checkPin(p,p.jwt()?state.plex.store.deviceJwt():""),token->{
            if(token.isBlank()) { showCode(); return; } owner=token; chooseChild();
        },this::checkCode);
    }
    private void chooseChild() {
        final String token=owner; if(token==null) return;
        work("Choose Plex child",()->api.users(token).stream().filter(u->u.restricted()&&!u.admin()).toList(),users->{
            if(users.isEmpty()) { dialog=new AlertDialog.Builder(activity).setTitle("No restricted child profiles")
                    .setMessage("Connect a Plex Home with a restricted child profile to use personal TV here.").setPositiveButton("OK",null).show(); owner=null; return; }
            dialog=new AlertDialog.Builder(activity).setTitle("Choose Plex child")
                    .setItems(users.stream().map(PlexApi.HomeUser::name).toArray(String[]::new),(d,w)->{
                        PlexApi.HomeUser u=users.get(w);
                        if(u.protectedPin()) {
                            EditText field=new EditText(activity); field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                            dialog=new AlertDialog.Builder(activity).setTitle("Plex profile PIN").setView(field).setNegativeButton("Cancel",null)
                                    .setPositiveButton("Continue",(dd,ww)->selectChild(token,u,field.getText().toString())).show();
                        } else selectChild(token,u,"");
                    }).setNegativeButton("Cancel",(d,w)->{owner=null;}).show();
        },this::chooseChild);
    }
    private void selectChild(String token,PlexApi.HomeUser u,String pinValue) {
        work("Verify Plex child",()->api.selectChild(token,u,pinValue),child->{owner=null; pin=null; chooseServer(child);},this::chooseChild);
    }
    private void chooseServer(PlexApi.Child child) {
        work("Choose Plex server",()->api.servers(child),servers->{
            if(servers.isEmpty()) { dialog=new AlertDialog.Builder(activity).setTitle("No Plex servers available")
                    .setMessage("This child cannot access a personal media server.").setPositiveButton("OK",null).show(); return; }
            dialog=new AlertDialog.Builder(activity).setTitle("Choose Plex server")
                    .setItems(servers.stream().map(PlexApi.Server::name).toArray(String[]::new),(d,w)->route(child,servers.get(w),null))
                    .setNegativeButton("Cancel",null).show();
        },()->chooseServer(child));
    }
    private void route(PlexApi.Child child,PlexApi.Server server,String previous) {
        if(!valid()) return;
        EditText field=new EditText(activity); field.setSingleLine(); field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        field.setHint("https://your-plex-server");
        PlexApi.Session current=state.plex.session();
        field.setText(previous!=null?previous:current!=null && current.serverId().equals(server.id())?current.origin():server.connections().stream().findFirst().orElse(""));
        dialog=new AlertDialog.Builder(activity).setTitle("Confirm HTTPS Plex route")
                .setMessage("Use your normal HTTPS server address. TrusTube checks its server identity before sending the child's credential.")
                .setView(field).setNegativeButton("Cancel",null).setPositiveButton("Verify and save",(d,w)->verify(child,server,field.getText().toString().trim())).show();
    }
    private void verify(PlexApi.Child child,PlexApi.Server server,String url) {
        work("Verify Plex server",()-> {
            PlexApi.Session s=api.connect(child,server,url,state.plex.store.nextGeneration());
            api.verifyChild(child); api.tvLibraries(s); return s;
        },s->{
            synchronized(this) {
                if(!valid()) return;
                try { state.plex.store.save(s,setupClient); state.plex.connected(); }
                catch(Exception e) { Ui.error(activity,e); }
            }
        },()->route(child,server,url));
    }
}
