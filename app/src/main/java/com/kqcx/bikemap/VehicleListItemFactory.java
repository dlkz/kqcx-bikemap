package com.kqcx.bikemap;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class VehicleListItemFactory {
    interface Host {
        boolean isSelected(Bike bike);

        String siteName(Bike bike);

        String distanceLabel(Bike bike);

        String batteryLabel(Bike bike);

        int cardWidth();

        void locate(Bike bike);

        void select(Bike bike);
    }

    private final Context context;
    private final Host host;

    VehicleListItemFactory(Context context, Host host) {
        this.context = context;
        this.host = host;
    }

    View createCard(Bike bike) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(6), dp(12), dp(6));
        card.setBackgroundResource(host.isSelected(bike)
                ? R.drawable.bg_vehicle_list_card_selected
                : R.drawable.bg_vehicle_list_card);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView number = textView("#" + bike.number, 18, R.color.text_primary, true);
        number.setSingleLine(true);
        number.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(
                number,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
        );

        TextView action = textView(
                context.getString(R.string.action_locate),
                13,
                R.color.control_accent_text,
                true
        );
        action.setGravity(Gravity.CENTER);
        action.setClickable(true);
        action.setFocusable(true);
        action.setContentDescription(
                context.getString(R.string.action_locate) + " " + bike.number
        );
        TypedValue actionBackground = new TypedValue();
        if (context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                actionBackground,
                true
        )) {
            Drawable background = context.getDrawable(actionBackground.resourceId);
            action.setBackground(background);
        }
        action.setOnClickListener(view -> host.locate(bike));
        titleRow.addView(action, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.addView(titleRow);

        String site = host.siteName(bike);
        if (!site.isEmpty()) {
            TextView siteView = textView(site, 14, R.color.text_secondary, false);
            siteView.setSingleLine(true);
            siteView.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(siteView);
        }

        TextView meta = textView(
                host.distanceLabel(bike)
                        + " · " + host.batteryLabel(bike)
                        + " · " + context.getString(bike.statusRes),
                12,
                R.color.text_tertiary,
                false
        );
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(meta);

        card.setLayoutParams(new LinearLayout.LayoutParams(
                host.cardWidth(),
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        card.setOnClickListener(view -> host.select(bike));
        return card;
    }

    View createRow(Bike bike) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(9), dp(8), dp(9));
        row.setBackgroundResource(R.drawable.bg_list_row);

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_bike_marker);
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, dp(8), 0);
        labels.addView(textView("#" + bike.number, 17, R.color.text_primary, true));

        String site = host.siteName(bike);
        if (!site.isEmpty()) {
            labels.addView(textView(site, 13, R.color.text_secondary, false));
        }
        labels.addView(textView(
                host.distanceLabel(bike)
                        + " · " + host.batteryLabel(bike)
                        + " · " + context.getString(bike.statusRes),
                11,
                R.color.text_tertiary,
                false
        ));
        row.addView(
                labels,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
        );

        TextView action = textView(
                context.getString(R.string.action_locate),
                13,
                R.color.control_accent_text,
                true
        );
        action.setGravity(Gravity.CENTER);
        row.addView(
                action,
                new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT)
        );

        row.setOnClickListener(view -> host.select(bike));
        return row;
    }

    private TextView textView(String text, int sizeSp, int colorRes, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(context.getColor(colorRes));
        if (bold) {
            view.setTypeface(view.getTypeface(), Typeface.BOLD);
        }
        return view;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
