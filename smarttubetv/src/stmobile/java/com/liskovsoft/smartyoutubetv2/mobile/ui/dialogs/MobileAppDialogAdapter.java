package com.liskovsoft.smartyoutubetv2.mobile.ui.dialogs;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Flat list of section headers + option rows, built from a list of
 * {@link OptionCategory}. The category type controls which left-side indicator (radio /
 * checkbox) and whether to show a right-side switch — but a single tap on a row always
 * runs the same code path: flip {@link OptionItem#isSelected()} and call onSelect, then
 * re-render.
 *
 * Radio behavior is enforced locally — picking an item in a radio category clears the
 * selected state of its siblings via {@code onSelect(false)} before activating the new one.
 */
class MobileAppDialogAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_OPTION = 1;
    private static final int TYPE_LONG_TEXT = 2;
    private static final int TYPE_VALUE = 3;
    private static final int TYPE_SLIDER = 4;

    /** Fired after an option is picked. Used by the bottom-sheet picker to dismiss + refresh. */
    interface OnSelectListener {
        void onSelected();
    }

    private final List<Row> mRows = new ArrayList<>();
    private OnSelectListener mOnSelect;

    void setOnSelectListener(OnSelectListener listener) {
        mOnSelect = listener;
    }

    void setCategories(List<OptionCategory> categories, boolean isExpandable) {
        mRows.clear();
        if (categories == null) {
            notifyDataSetChanged();
            return;
        }
        // Suppress the category-header row when it would be redundant with the dialog
        // title (expandable single category — the fragment already shows category.title up
        // top, see MobileAppDialogFragment#show).
        boolean suppressHeaders = isExpandable && categories.size() == 1;
        for (OptionCategory category : categories) {
            if (category.options == null || category.options.isEmpty()) {
                continue;
            }
            // A radio category whose options form an ordered numeric range (speed, zoom, seek
            // interval, auto-hide timeout) renders as a discrete slider over the option list.
            if (shouldSlider(category, suppressHeaders)) {
                mRows.add(Row.slider(category));
                continue;
            }
            // Other single-choice (radio) value lists collapse into one row showing the current
            // value; tapping it opens a bottom-sheet picker. Only on multi-category screens
            // (suppressHeaders == false) — a single expandable category stays expanded so the
            // user can pick directly (this also prevents the picker's own list from recursing).
            if (shouldCollapse(category, suppressHeaders)) {
                mRows.add(Row.value(category));
                continue;
            }
            if (!suppressHeaders && !TextUtils.isEmpty(category.title)
                    && category.type != OptionCategory.TYPE_SINGLE_SWITCH
                    && category.type != OptionCategory.TYPE_SINGLE_BUTTON) {
                mRows.add(Row.header(category.title));
            }
            for (OptionItem item : category.options) {
                if (category.type == OptionCategory.TYPE_LONG_TEXT) {
                    mRows.add(Row.longText(item));
                } else {
                    mRows.add(Row.option(category, item));
                }
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    @Override
    public int getItemViewType(int position) {
        switch (mRows.get(position).kind) {
            case HEADER: return TYPE_HEADER;
            case LONG_TEXT: return TYPE_LONG_TEXT;
            case VALUE: return TYPE_VALUE;
            case SLIDER: return TYPE_SLIDER;
            case OPTION:
            default: return TYPE_OPTION;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_HEADER:
                return new HeaderHolder(
                        inflater.inflate(R.layout.mobile_app_dialog_header, parent, false));
            case TYPE_LONG_TEXT:
                return new LongTextHolder(
                        inflater.inflate(R.layout.mobile_app_dialog_longtext, parent, false));
            case TYPE_VALUE:
                return new ValueHolder(
                        inflater.inflate(R.layout.mobile_app_dialog_value, parent, false));
            case TYPE_SLIDER:
                return new SliderHolder(
                        inflater.inflate(R.layout.mobile_app_dialog_slider, parent, false));
            case TYPE_OPTION:
            default:
                return new OptionHolder(
                        inflater.inflate(R.layout.mobile_app_dialog_option, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = mRows.get(position);
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).title.setText(row.title);
        } else if (holder instanceof LongTextHolder) {
            CharSequence body = row.item.getDescription();
            if (TextUtils.isEmpty(body)) {
                body = row.item.getTitle();
            }
            ((LongTextHolder) holder).body.setText(body);
        } else if (holder instanceof ValueHolder) {
            bindValue((ValueHolder) holder, row);
        } else if (holder instanceof SliderHolder) {
            bindSlider((SliderHolder) holder, row);
        } else if (holder instanceof OptionHolder) {
            bindOption((OptionHolder) holder, row);
        }
    }

    private void bindValue(ValueHolder h, Row row) {
        OptionCategory category = row.category;
        h.title.setText(category.title);
        OptionItem selected = selectedOf(category);
        h.value.setText(selected != null ? selected.getTitle() : "");
        h.itemView.setOnClickListener(v -> showPicker(v, category));
    }

    /** Open a bottom sheet listing the category's options as the standard expanded radio list. */
    private void showPicker(View anchor, OptionCategory category) {
        BottomSheetDialog sheet = new BottomSheetDialog(anchor.getContext());
        View content = LayoutInflater.from(anchor.getContext())
                .inflate(R.layout.mobile_app_dialog_picker, null);

        ((TextView) content.findViewById(R.id.picker_title)).setText(category.title);

        RecyclerView list = content.findViewById(R.id.picker_list);
        list.setLayoutManager(new LinearLayoutManager(anchor.getContext()));
        MobileAppDialogAdapter inner = new MobileAppDialogAdapter();
        // A single expandable category renders expanded radio rows (suppressHeaders == true), so
        // there's no recursion back into this collapsed-value path.
        inner.setCategories(Collections.singletonList(category), true);
        inner.setOnSelectListener(() -> {
            sheet.dismiss();
            notifyDataSetChanged(); // refresh the collapsed row's current value
        });
        list.setAdapter(inner);

        sheet.setContentView(content);
        sheet.show();
    }

    private void bindSlider(SliderHolder h, Row row) {
        final OptionCategory category = row.category;
        final List<OptionItem> options = category.options;
        h.title.setText(category.title);

        int selectedIndex = 0;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).isSelected()) {
                selectedIndex = i;
                break;
            }
        }
        h.value.setText(options.get(selectedIndex).getTitle());

        // Recycled holder: drop the previous listener before reconfiguring, so the programmatic
        // setProgress() below doesn't fire a stale callback against another category.
        h.seek.setOnSeekBarChangeListener(null);
        h.seek.setMax(options.size() - 1);
        h.seek.setProgress(selectedIndex);

        h.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                // Live label feedback while dragging; the actual selection is committed on release.
                if (fromUser && progress >= 0 && progress < options.size()) {
                    h.value.setText(options.get(progress).getTitle());
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                int index = bar.getProgress();
                if (index < 0 || index >= options.size()) {
                    return;
                }
                OptionItem pick = options.get(index);
                // Same mutual-exclusion path as a radio tap: clear the previously selected
                // sibling, then activate the new one.
                for (OptionItem sibling : options) {
                    if (sibling != pick && sibling.isSelected()) {
                        sibling.onSelect(false);
                    }
                }
                pick.onSelect(true);
                if (mOnSelect != null) {
                    mOnSelect.onSelected();
                }
            }
        });
    }

    private void bindOption(OptionHolder h, Row row) {
        OptionItem item = row.item;
        h.title.setText(item.getTitle());
        CharSequence desc = item.getDescription();
        if (TextUtils.isEmpty(desc)) {
            h.description.setVisibility(View.GONE);
        } else {
            h.description.setVisibility(View.VISIBLE);
            h.description.setText(desc);
        }

        h.radio.setVisibility(View.GONE);
        h.check.setVisibility(View.GONE);
        h.toggle.setVisibility(View.GONE);

        switch (row.categoryType) {
            case OptionCategory.TYPE_RADIO_LIST:
                h.radio.setVisibility(View.VISIBLE);
                h.radio.setChecked(item.isSelected());
                break;
            case OptionCategory.TYPE_CHECKBOX_LIST:
                h.check.setVisibility(View.VISIBLE);
                h.check.setChecked(item.isSelected());
                break;
            case OptionCategory.TYPE_SINGLE_SWITCH:
                h.toggle.setVisibility(View.VISIBLE);
                h.toggle.setChecked(item.isSelected());
                break;
            case OptionCategory.TYPE_STRING_LIST:
            case OptionCategory.TYPE_SINGLE_BUTTON:
            case OptionCategory.TYPE_CHAT:
            case OptionCategory.TYPE_COMMENTS:
            default:
                // No indicator — just a tappable row.
                break;
        }

        h.itemView.setOnClickListener(v -> onOptionClicked(row));
    }

    private void onOptionClicked(Row row) {
        OptionItem item = row.item;
        switch (row.categoryType) {
            case OptionCategory.TYPE_RADIO_LIST: {
                // Mutual exclusion: clear siblings (their callbacks will tear down any
                // side effects), then activate the new pick.
                if (row.siblings != null) {
                    for (OptionItem sibling : row.siblings) {
                        if (sibling != item && sibling.isSelected()) {
                            sibling.onSelect(false);
                        }
                    }
                }
                item.onSelect(true);
                break;
            }
            case OptionCategory.TYPE_CHECKBOX_LIST:
            case OptionCategory.TYPE_SINGLE_SWITCH:
                item.onSelect(!item.isSelected());
                break;
            case OptionCategory.TYPE_STRING_LIST:
            case OptionCategory.TYPE_SINGLE_BUTTON:
            case OptionCategory.TYPE_CHAT:
            case OptionCategory.TYPE_COMMENTS:
            default:
                item.onSelect(true);
                break;
        }
        // Re-render the whole list — a radio change touches multiple rows, and a callback
        // may have mutated the category contents (the presenter sometimes reopens or
        // replaces the dialog, which is harmless to re-bind against).
        notifyDataSetChanged();

        if (mOnSelect != null) {
            mOnSelect.onSelected();
        }
    }

    /** Whether a radio category should be shown collapsed as a single value row + picker. */
    private static boolean shouldCollapse(OptionCategory category, boolean suppressHeaders) {
        return !suppressHeaders
                && category.type == OptionCategory.TYPE_RADIO_LIST
                && !TextUtils.isEmpty(category.title)
                && selectedOf(category) != null; // a value choice, not an action list
    }

    /**
     * Whether a collapsible radio category is an ordered numeric range (speed, zoom, seek
     * interval, auto-hide timeout) and should be a discrete slider instead of a picker. Detected
     * from the option labels — a strong majority must contain a number (a few word labels like
     * "Never" / "Default" at the ends are fine).
     */
    private static boolean shouldSlider(OptionCategory category, boolean suppressHeaders) {
        if (!shouldCollapse(category, suppressHeaders) || category.options.size() < 3) {
            return false;
        }
        int numeric = 0;
        for (OptionItem option : category.options) {
            if (hasDigit(option.getTitle())) {
                numeric++;
            }
        }
        return numeric * 5 >= category.options.size() * 3; // >= 60% numeric
    }

    private static boolean hasDigit(CharSequence text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static OptionItem selectedOf(OptionCategory category) {
        if (category.options != null) {
            for (OptionItem option : category.options) {
                if (option.isSelected()) {
                    return option;
                }
            }
        }
        return null;
    }

    // ----- view holders -----

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        HeaderHolder(View v) {
            super(v);
            title = v.findViewById(R.id.category_title);
        }
    }

    static class OptionHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView description;
        final RadioButton radio;
        final CheckBox check;
        final SwitchCompat toggle;
        OptionHolder(View v) {
            super(v);
            title = v.findViewById(R.id.option_title);
            description = v.findViewById(R.id.option_description);
            radio = v.findViewById(R.id.option_radio);
            check = v.findViewById(R.id.option_check);
            toggle = v.findViewById(R.id.option_switch);
        }
    }

    static class LongTextHolder extends RecyclerView.ViewHolder {
        final TextView body;
        LongTextHolder(View v) {
            super(v);
            body = v.findViewById(R.id.longtext_body);
        }
    }

    static class ValueHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView value;
        ValueHolder(View v) {
            super(v);
            title = v.findViewById(R.id.value_title);
            value = v.findViewById(R.id.value_current);
        }
    }

    static class SliderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView value;
        final AppCompatSeekBar seek;
        SliderHolder(View v) {
            super(v);
            title = v.findViewById(R.id.slider_title);
            value = v.findViewById(R.id.slider_value);
            seek = v.findViewById(R.id.slider_seek);
        }
    }

    // ----- row model -----

    private enum Kind { HEADER, OPTION, LONG_TEXT, VALUE, SLIDER }

    private static class Row {
        final Kind kind;
        final CharSequence title;
        final OptionItem item;
        final int categoryType;
        final List<OptionItem> siblings;
        final OptionCategory category;

        private Row(Kind kind, CharSequence title, OptionItem item,
                    int categoryType, List<OptionItem> siblings, OptionCategory category) {
            this.kind = kind;
            this.title = title;
            this.item = item;
            this.categoryType = categoryType;
            this.siblings = siblings;
            this.category = category;
        }

        static Row header(CharSequence title) {
            return new Row(Kind.HEADER, title, null, -1, null, null);
        }

        static Row option(OptionCategory category, OptionItem item) {
            return new Row(Kind.OPTION, null, item, category.type, category.options, null);
        }

        static Row longText(OptionItem item) {
            return new Row(Kind.LONG_TEXT, null, item, OptionCategory.TYPE_LONG_TEXT, null, null);
        }

        static Row value(OptionCategory category) {
            return new Row(Kind.VALUE, null, null, category.type, category.options, category);
        }

        static Row slider(OptionCategory category) {
            return new Row(Kind.SLIDER, null, null, category.type, category.options, category);
        }
    }
}
