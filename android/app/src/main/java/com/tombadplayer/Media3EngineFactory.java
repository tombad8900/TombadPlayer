package com.tombadplayer;

import android.content.Context;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

public final class Media3EngineFactory {
    private Media3EngineFactory() {}

    public static ExoPlayer create(Context context) {
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 60000, 1500, 3000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true);
        return new ExoPlayer.Builder(context, renderers)
                .setLoadControl(load)
                .build();
    }
}
