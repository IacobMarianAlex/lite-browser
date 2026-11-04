package ro.lite.browser;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;

import java.util.function.BooleanSupplier;

/**
 * WebView care, cat timp reda sunet, nu afla ca ecranul s-a stins.
 * Altfel motorul web pune clipul pe pauza in momentul in care blochezi telefonul.
 */
public class MediaWebView extends WebView {

    BooleanSupplier keepPlaying;

    public MediaWebView(Context c) {
        super(c);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (visibility != View.VISIBLE && keepPlaying != null && keepPlaying.getAsBoolean()) {
            super.onWindowVisibilityChanged(View.VISIBLE);
            return;
        }
        super.onWindowVisibilityChanged(visibility);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        if (visibility != View.VISIBLE && keepPlaying != null && keepPlaying.getAsBoolean()) {
            super.onVisibilityChanged(changedView, View.VISIBLE);
            return;
        }
        super.onVisibilityChanged(changedView, visibility);
    }
}
