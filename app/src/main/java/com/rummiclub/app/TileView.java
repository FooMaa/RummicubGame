package com.rummiclub.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import com.rummiclub.core.Rules;

@android.annotation.SuppressLint("ViewConstructor") // Created in code with an immutable tile identity, never inflated from XML.
final class TileView extends View {
    private static final int[] COLORS = {0xFFCB5048, 0xFF3272B8, 0xFFD39428, 0xFF27343E};
    private static final String[] NAMES = {"красный", "синий", "жёлтый", "чёрный"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int tile;
    private final boolean selected;
    private int insertion;
    void insertion(int side){insertion=side;invalidate();}
    TileView(Context context, int tile, boolean selected) {
        super(context); this.tile = tile; this.selected = selected; setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription(Rules.joker(tile) ? "Джокер" : NAMES[Rules.color(tile)] + " " + Rules.number(tile));
        setFocusable(true); setClickable(true);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), unit = w / 48;
        paint.setColor(selected ? 0xFFC5F277 : 0xFFF6F3E8);
        paint.setShadowLayer(2 * unit, 0, 2 * unit, 0x45000000);
        canvas.drawRoundRect(2 * unit, 2 * unit, w - 2 * unit, h - 4 * unit, 8 * unit, 8 * unit, paint);
        paint.clearShadowLayer();
        paint.setColor(Rules.joker(tile) ? 0xFF7954AF : COLORS[Rules.color(tile)]);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)); paint.setTextSize(29 * unit); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(Rules.joker(tile) ? "★" : String.valueOf(Rules.number(tile)), w / 2, h * .62f, paint);
        if(insertion!=0){paint.setColor(0xFFC5F277);paint.setStrokeWidth(4*unit);float x=insertion<0?unit:w-unit;canvas.drawLine(x,4*unit,x,h-4*unit,paint);}
        // Color-independent marks: 1–4 dots, so tiles remain distinguishable without color.
        int dots = Rules.joker(tile) ? 0 : Rules.color(tile) + 1;
        for (int i = 0; i < dots; i++) canvas.drawCircle(w / 2 + (i - (dots - 1) / 2f) * 5 * unit, h * .79f, 1.35f * unit, paint);
    }
}
