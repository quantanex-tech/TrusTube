// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** One viewport observer per window. Offscreen views retain placeholders, not bitmaps/jobs. */
final class LazyImages implements ViewTreeObserver.OnPreDrawListener,
        ViewTreeObserver.OnScrollChangedListener, ViewTreeObserver.OnGlobalLayoutListener {
    private final Set<Binding> bindings = new LinkedHashSet<>();
    private final Map<View, Integer> windows = new HashMap<>();
    private boolean active = true, dirty = true;

    private void observe(View root) {
        Integer count = windows.get(root);
        windows.put(root, count == null ? 1 : count + 1);
        if (count != null) return;
        ViewTreeObserver observer = root.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        observer.addOnScrollChangedListener(this);
        observer.addOnGlobalLayoutListener(this);
    }
    private void unobserve(View root) {
        int count = windows.get(root);
        if (count > 1) { windows.put(root, count - 1); return; }
        windows.remove(root);
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (!observer.isAlive()) return;
        observer.removeOnPreDrawListener(this);
        observer.removeOnScrollChangedListener(this);
        observer.removeOnGlobalLayoutListener(this);
    }
    private static LazyImages forActivity(Activity activity) {
        View root = activity.getWindow().getDecorView();
        LazyImages images = (LazyImages) root.getTag(R.id.lazy_images);
        if (images == null) { images = new LazyImages(); root.setTag(R.id.lazy_images, images); }
        return images;
    }
    static void bind(Activity activity, ImageView view, String url, ImageCache cache) {
        Binding previous = (Binding) view.getTag(R.id.image_binding);
        if (previous != null) previous.dispose();
        view.setTag(R.id.image_binding, null);
        view.setTag(url);
        if (!cache.accepts(url)) return;
        LazyImages images = forActivity(activity);
        Binding binding = images.new Binding(view, url, cache);
        view.setTag(R.id.image_binding, binding);
        view.addOnAttachStateChangeListener(binding);
        if (view.isAttachedToWindow()) binding.onViewAttachedToWindow(view);
    }
    static void setActive(Activity activity, boolean value) {
        LazyImages images = forActivity(activity);
        images.active = value; images.dirty = true;
        if (!value) for (Binding binding : new ArrayList<>(images.bindings)) binding.reset();
        else activity.getWindow().getDecorView().invalidate();
    }
    @Override public boolean onPreDraw() {
        if (dirty) {
            dirty = false;
            for (Binding binding : new ArrayList<>(bindings)) binding.update();
        }
        return true;
    }
    @Override public void onScrollChanged() { dirty = true; }
    @Override public void onGlobalLayout() { dirty = true; }
    private final class Binding implements View.OnAttachStateChangeListener {
        final ImageView view;
        final String url;
        final ImageCache cache;
        final Drawable placeholder;
        final Rect visible = new Rect();
        ImageCache.Subscription request;
        boolean loaded;
        View window;
        long generation;
        Binding(ImageView view, String url, ImageCache cache) {
            this.view = view; this.url = url; this.cache = cache; placeholder = view.getDrawable();
        }
        boolean visible() {
            return active && view.isAttachedToWindow() && view.getWindowVisibility() == View.VISIBLE
                    && view.isShown() && view.getGlobalVisibleRect(visible) && !visible.isEmpty();
        }
        void update() {
            if (!visible()) { reset(); return; }
            if (loaded || request != null) return;
            final long token = generation;
            request = cache.load(url, bitmap -> {
                if (token != generation || !visible() || view.getTag(R.id.image_binding) != this) return;
                request = null; loaded = true; view.setImageBitmap(bitmap);
            });
        }
        void reset() {
            if (request != null) { request.cancel(); request = null; }
            if (loaded) { view.setImageDrawable(placeholder); loaded = false; }
            generation++;
        }
        @Override public void onViewAttachedToWindow(View ignored) {
            if (!bindings.add(this)) return;
            window = view.getRootView(); observe(window); dirty = true;
        }
        @Override public void onViewDetachedFromWindow(View ignored) {
            if (bindings.remove(this)) { unobserve(window); window = null; }
            reset();
        }
        void dispose() {
            onViewDetachedFromWindow(view); view.removeOnAttachStateChangeListener(this);
        }
    }
}
