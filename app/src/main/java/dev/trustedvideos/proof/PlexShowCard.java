// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;
import dev.trustedvideos.proof.core.VideoEntry;

/** Resume resolution begins only when this card is visible; artwork uses the same lazy viewport. */
final class PlexShowCard {
    static LinearLayout create(Activity a,ProofState state,String key,Consumer<VideoEntry> play,Runnable browse) {
        LinearLayout card=new LinearLayout(a); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(a,8),Ui.dp(a,8),Ui.dp(a,8),Ui.dp(a,10));
        card.setBackground(Ui.background(Color.WHITE,16,a));
        ImageView art=new ImageView(a); art.setImageResource(R.drawable.ic_channels); art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art,new LinearLayout.LayoutParams(-1,Ui.dp(a,170))); Ui.image(a,state,art,state.avatar(key));
        card.addView(Ui.text(a,state.label(key)+" · TV",17,true));
        TextView episode=Ui.text(a,"Finding your episode…",13,false); card.addView(episode);
        android.widget.Button action=Ui.button(a,"Continue / Start",()->{}); action.setEnabled(false); card.addView(action);
        card.addView(Ui.button(a,"Browse episodes",browse));
        card.setOnLongClickListener(v->{ new android.app.AlertDialog.Builder(a).setTitle("Block this show?")
                .setMessage("Hide "+state.label(key)+" and all its episodes? Only a parent can restore it.")
                .setNegativeButton("Cancel",null).setPositiveButton("Block show",(d,w)->state.blockChannel(key)).show(); return true; });
        card.setContentDescription(state.label(key)+" · TV show");
        ViewTreeObserver.OnPreDrawListener listener=new ViewTreeObserver.OnPreDrawListener() {
            boolean requested;
            @Override public boolean onPreDraw() {
                Rect r=new Rect();
                if(!requested && card.isShown() && card.getGlobalVisibleRect(r) && !r.isEmpty()) {
                    requested=true;
                    state.plex.target(key,target->{
                        if(!card.isAttachedToWindow() || a.isDestroyed() || !state.policy.allowsChannel(key)) return;
                        bindAction(a,state,key,card,episode,action,target,play);
                    });
                }
                return true;
            }
        };
        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { card.getViewTreeObserver().addOnPreDrawListener(listener); }
            public void onViewDetachedFromWindow(View v) { if(card.getViewTreeObserver().isAlive()) card.getViewTreeObserver().removeOnPreDrawListener(listener); }
        });
        return card;
    }
    private static void bindAction(Activity a,ProofState state,String key,LinearLayout card,TextView episode,
                                   android.widget.Button action,PlexLibrary.Target target,Consumer<VideoEntry> play) {
        episode.setText(target.video()==null?target.error():target.video().title);
        action.setText(target.action().isEmpty()?"Unavailable":target.action()); action.setEnabled(target.video()!=null);
        action.setOnClickListener(v->{
            action.setEnabled(false); state.plex.invalidateTarget(key);
            state.plex.target(key,fresh->{
                if(!card.isAttachedToWindow() || a.isDestroyed() || !state.policy.allowsChannel(key)) return;
                bindAction(a,state,key,card,episode,action,fresh,play);
                if(fresh.video()!=null && target.video()!=null && fresh.video().id.equals(target.video().id)) play.accept(fresh.video());
                else android.widget.Toast.makeText(a,"Your next episode changed. Check the title, then play.",android.widget.Toast.LENGTH_SHORT).show();
            });
        });
    }

}
