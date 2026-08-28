package com.litiansuo.purifier.rules.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 统一的勾选框视图。
 *
 * <p>替换系统 {@link android.widget.CheckBox}——系统在 Material 主题下有自己的勾选图标，
 * 跟李田所面板风格不搭。本控件自己画：</p>
 *
 * <ul>
 *   <li><b>未选中</b>：空心圆 + 灰边；</li>
 *   <li><b>已选中</b>：蓝实心圆 + 白色对勾；</li>
 *   <li>蓝色取自 Context 的 {@code textColorLink}（融入系统主题），fallback {@code #1E88E5}；</li>
 *   <li>固定尺寸 22dp × 22dp；</li>
 *   <li>可点击，会 toggle 状态并触发 listener；</li>
 *   <li>无障碍：实现 {@link View#setSelected} 与 {@link View#isSelected} 语义，外部
 *       CheckBox.setChecked 仍用 {@code setSelected} 替代。</li>
 * </ul>
 *
 * <p>注意：因为完全自绘，{@code CompoundButton.OnCheckedChangeListener} 不能直接挂——
 * 用 {@link #setOnCheckedChangeListener} 替代（{@code Runnable}，无参）。</p>
 */
public class UniformCheckbox extends View {

    private boolean checked;
    private Runnable onCheckedChange;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path checkPath = new Path();
    private final int ringColor;
    private final int accentColor;
    private final float ringStrokePx;
    private final float boxSizePx;

    public UniformCheckbox(Context ctx) {
        this(ctx, null);
    }

    public UniformCheckbox(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float density = ctx.getResources().getDisplayMetrics().density;
        boxSizePx = 22f * density;
        ringStrokePx = 1.5f * density;

        ringColor = Color.parseColor("#BDBDBD");      // 浅灰边
        accentColor = Color.parseColor("#4CAF50");    // 绿色实心圆（与 QQ音乐已勾选一致）
        checkPaint.setColor(Color.WHITE);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(ringStrokePx);
        ringPaint.setColor(ringColor);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(accentColor);

        // cb 本身不消费点击，让事件冒泡到外层 row；外层 row 触发 toggle()
        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = (int) (boxSizePx + ringStrokePx * 2 + getPaddingLeft() + getPaddingRight());
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = boxSizePx / 2f;

        if (checked) {
            // 实心蓝圆
            canvas.drawCircle(cx, cy, r, fillPaint);
            // 白色对勾
            checkPath.reset();
            float k = boxSizePx / 22f; // 比例
            checkPath.moveTo(cx - 5f * k, cy + 0.5f * k);
            checkPath.lineTo(cx - 1.5f * k, cy + 4f * k);
            checkPath.lineTo(cx + 5.5f * k, cy - 3.5f * k);
            checkPaint.setStrokeWidth(2f * density());
            checkPaint.setStyle(Paint.Style.STROKE);
            checkPaint.setStrokeCap(Paint.Cap.ROUND);
            checkPaint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(checkPath, checkPaint);
        } else {
            // 空心灰圆
            canvas.drawCircle(cx, cy, r - ringStrokePx / 2f, ringPaint);
        }
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    public boolean isChecked() {
        return checked;
    }

    /** 替代 {@code CompoundButton#setChecked}。 */
    public void setChecked(boolean c) {
        if (this.checked != c) {
            this.checked = c;
            setSelected(c); // 给无障碍/动画用
            invalidate();
            if (onCheckedChange != null) {
                onCheckedChange.run();
            }
        }
    }

    /** 替代 {@code CompoundButton#setOnCheckedChangeListener}。listener 不带参数，自己读 isChecked()。 */
    public void setOnCheckedChangeListener(Runnable r) {
        this.onCheckedChange = r;
    }

    @Override
    public boolean performClick() {
        // 自身不消费点击事件；外层 row 在 onClick 里调 toggle()
        return false;
    }

    public void toggle() {
        setChecked(!checked);
    }
}
