package com.kqcx.bikemap;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

final class VehicleListPanelController {
    interface Host {
        boolean isSelected(Bike bike);

        String siteName(Bike bike);

        String distanceLabel(Bike bike);

        String batteryLabel(Bike bike);

        void locate(Bike bike);

        void select(Bike bike);
    }

    private static final long ANIMATION_MS = 220L;
    private static final long CARD_SNAP_DELAY_MS = 180L;
    private static final int DRAG_THRESHOLD_DP = 14;

    private final Context context;
    private final LinearLayout panel;
    private final TextView subtitle;
    private final TextView expandButton;
    private final HorizontalScrollView cardScroll;
    private final LinearLayout cardsRow;
    private final ScrollView listScroll;
    private final LinearLayout listRows;
    private final View leftControls;
    private final View listButton;
    private final View refreshButton;
    private final VehicleListItemFactory itemFactory;

    private ValueAnimator animator;
    private boolean expanded;
    private int bikeCount;
    private int bottomInset;

    VehicleListPanelController(Context context, View root, Host host) {
        this.context = context;
        panel = root.findViewById(R.id.vehicleListPanel);
        subtitle = root.findViewById(R.id.tvVehicleListSubtitle);
        expandButton = root.findViewById(R.id.btnVehicleListExpand);
        cardScroll = root.findViewById(R.id.vehicleListCardScroll);
        cardsRow = root.findViewById(R.id.vehicleListCardsRow);
        listScroll = root.findViewById(R.id.vehicleListScroll);
        listRows = root.findViewById(R.id.vehicleListRows);
        leftControls = root.findViewById(R.id.leftControls);
        listButton = root.findViewById(R.id.btnList);
        refreshButton = root.findViewById(R.id.btnRefresh);
        itemFactory = new VehicleListItemFactory(
                context,
                new VehicleListItemFactory.Host() {
                    @Override
                    public boolean isSelected(Bike bike) {
                        return host.isSelected(bike);
                    }

                    @Override
                    public String siteName(Bike bike) {
                        return host.siteName(bike);
                    }

                    @Override
                    public String distanceLabel(Bike bike) {
                        return host.distanceLabel(bike);
                    }

                    @Override
                    public String batteryLabel(Bike bike) {
                        return host.batteryLabel(bike);
                    }

                    @Override
                    public int cardWidth() {
                        return VehicleListPanelController.this.cardWidth();
                    }

                    @Override
                    public void locate(Bike bike) {
                        host.locate(bike);
                    }

                    @Override
                    public void select(Bike bike) {
                        host.select(bike);
                    }
                }
        );
    }

    @SuppressLint("ClickableViewAccessibility")
    void setup() {
        panel.findViewById(R.id.btnVehicleListClose)
                .setOnClickListener(view -> hide());
        expandButton.setOnClickListener(view -> setExpanded(!expanded, true));

        View dragHandle = panel.findViewById(R.id.vehicleListDragHandle);
        dragHandle.setOnClickListener(view -> setExpanded(!expanded, true));
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private float downY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downY = event.getY();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    float delta = event.getY() - downY;
                    if (delta < -dp(DRAG_THRESHOLD_DP)) {
                        setExpanded(true, true);
                    } else if (delta > dp(DRAG_THRESHOLD_DP)) {
                        setExpanded(false, true);
                    } else {
                        view.performClick();
                    }
                    return true;
                }
                return event.getActionMasked() != MotionEvent.ACTION_CANCEL;
            }
        });

        cardScroll.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.postDelayed(this::snapCardsToNearest, CARD_SNAP_DELAY_MS);
            }
            return false;
        });
    }

    boolean isVisible() {
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    boolean isExpanded() {
        return expanded;
    }

    void show(List<Bike> bikes, boolean expanded) {
        render(bikes);
        panel.setVisibility(View.VISIBLE);
        setExpanded(expanded, false);
    }

    void refresh(List<Bike> bikes) {
        if (!isVisible()) {
            return;
        }
        if (bikes.isEmpty()) {
            hide();
            return;
        }
        render(bikes);
    }

    void setExpanded(boolean expanded, boolean animate) {
        this.expanded = expanded;
        if (!isVisible()) {
            return;
        }
        cardScroll.setVisibility(expanded ? View.GONE : View.VISIBLE);
        listScroll.setVisibility(expanded ? View.VISIBLE : View.GONE);
        updateHeader();
        animateHeight(
                expanded ? expandedHeight() : collapsedHeight(),
                animate
        );
        updateMapControls();
    }

    void hide() {
        cancelAnimation();
        expanded = false;
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        updateMapControls();
    }

    void setBottomInset(int bottomInset) {
        this.bottomInset = bottomInset;
        if (panel == null) {
            return;
        }
        ViewGroup.LayoutParams params = panel.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) params).bottomMargin = dp(88) + bottomInset;
            panel.setLayoutParams(params);
        }
    }

    void release() {
        cancelAnimation();
    }

    private void render(List<Bike> bikes) {
        cardsRow.removeAllViews();
        listRows.removeAllViews();
        cardScroll.scrollTo(0, 0);
        for (Bike bike : bikes) {
            cardsRow.addView(itemFactory.createCard(bike));
            listRows.addView(itemFactory.createRow(bike));
        }
        bikeCount = bikes.size();
        updateHeader();
    }

    private int cardWidth() {
        int viewportWidth = cardScroll.getWidth();
        if (viewportWidth <= 0) {
            viewportWidth = context.getResources().getDisplayMetrics().widthPixels - dp(56);
        }
        return Math.max(1, viewportWidth);
    }

    private void snapCardsToNearest() {
        int viewportWidth = cardScroll.getWidth();
        if (viewportWidth <= 0 || cardsRow.getChildCount() == 0) {
            return;
        }
        int page = Math.round(cardScroll.getScrollX() / (float) viewportWidth);
        page = Math.max(0, Math.min(cardsRow.getChildCount() - 1, page));
        cardScroll.smoothScrollTo(page * viewportWidth, 0);
    }

    private void updateHeader() {
        if (subtitle == null || expandButton == null) {
            return;
        }
        subtitle.setText(context.getString(
                expanded
                        ? R.string.list_subtitle
                        : R.string.vehicle_list_subtitle_cards,
                bikeCount
        ));
        expandButton.setText(
                expanded
                        ? R.string.vehicle_list_collapse
                        : R.string.vehicle_list_show_all
        );
    }

    private void animateHeight(int targetHeight, boolean animate) {
        cancelAnimation();
        ViewGroup.LayoutParams params = panel.getLayoutParams();
        int startHeight = panel.getHeight();
        if (!animate || startHeight <= 0) {
            params.height = targetHeight;
            panel.setLayoutParams(params);
            return;
        }
        ValueAnimator valueAnimator = ValueAnimator.ofInt(startHeight, targetHeight);
        valueAnimator.setDuration(ANIMATION_MS);
        valueAnimator.addUpdateListener(animation -> {
            params.height = (int) animation.getAnimatedValue();
            panel.setLayoutParams(params);
        });
        animator = valueAnimator;
        valueAnimator.start();
    }

    private void cancelAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private int collapsedHeight() {
        return dp(200);
    }

    private int expandedHeight() {
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        return Math.max(
                dp(280),
                Math.min(dp(520), Math.round(screenHeight * 0.58f))
        );
    }

    private void updateMapControls() {
        if (leftControls == null || listButton == null || refreshButton == null) {
            return;
        }
        if (!isVisible()) {
            leftControls.setVisibility(View.VISIBLE);
            listButton.setVisibility(View.VISIBLE);
            leftControls.setTranslationY(0f);
            refreshButton.setVisibility(View.VISIBLE);
            refreshButton.setTranslationY(0f);
        } else if (expanded) {
            leftControls.setVisibility(View.GONE);
            leftControls.setTranslationY(0f);
            refreshButton.setVisibility(View.GONE);
            refreshButton.setTranslationY(0f);
        } else {
            leftControls.setVisibility(View.VISIBLE);
            listButton.setVisibility(View.GONE);
            int overlap = collapsedHeight() + dp(88) + bottomInset - dp(276);
            float shift = -Math.max(0, overlap + dp(12));
            leftControls.setTranslationY(shift);
            refreshButton.setVisibility(View.VISIBLE);
            refreshButton.setTranslationY(shift - dp(10));
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
