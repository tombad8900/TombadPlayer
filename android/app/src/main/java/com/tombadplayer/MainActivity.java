package com.tombadplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(28, 28, 28, 28);
        layout.setBackgroundColor(Color.rgb(16, 18, 25));

        TextView title = new TextView(this);
        title.setText("TombadPlayer");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);

        TextView description = new TextView(this);
        description.setText(
                "مشغل فيديو وصوت مستقل\n" +
                "Media3 Native Engine"
        );
        description.setTextColor(Color.LTGRAY);
        description.setTextSize(16);
        description.setGravity(Gravity.CENTER);
        description.setPadding(0, 20, 0, 30);

        Button open = new Button(this);
        open.setText("فتح المشغل");
        open.setTextSize(17);
        open.setOnClickListener(v ->
                startActivity(new Intent(this, PlayerActivity.class)));

        layout.addView(title);
        layout.addView(description);
        layout.addView(open, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(layout);
    }
}
