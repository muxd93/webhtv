package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.ElderCard;
import com.fongmi.android.tv.db.AppDatabase;

import java.util.ArrayList;
import java.util.List;

public class ElderCardMenuDialog extends DialogFragment {

    private static final String ARG_CARD_ID = "card_id";

    private ElderCard card;
    private Callback callback;

    private static final int ACTION_PICK_COVER = 0;
    private static final int ACTION_RESTORE_COVER = 1;
    private static final int ACTION_MOVE_UP = 2;
    private static final int ACTION_MOVE_DOWN = 3;
    private static final int ACTION_REMOVE = 4;

    public interface Callback {
        void onCardChanged();
        void onPickCover(ElderCard card);
    }

    public static ElderCardMenuDialog create(ElderCard card) {
        ElderCardMenuDialog dialog = new ElderCardMenuDialog();
        Bundle args = new Bundle();
        args.putString(ARG_CARD_ID, card.getId());
        dialog.setArguments(args);
        dialog.card = card;
        return dialog;
    }

    public ElderCardMenuDialog callback(Callback callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
            if (fragment instanceof ElderCardMenuDialog) return;
        }
        show(activity.getSupportFragmentManager(), ElderCardMenuDialog.class.getSimpleName());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        if (callback == null && getActivity() instanceof Callback) {
            callback = (Callback) getActivity();
        }
        if (card == null) {
            String cardId = getArguments() != null ? getArguments().getString(ARG_CARD_ID) : null;
            if (cardId != null) card = AppDatabase.get().getElderCardDao().find(cardId);
            if (card == null) return new AlertDialog.Builder(requireContext()).setMessage("Card not found").create();
        }
        Context context = requireContext();
        boolean hasCustomCover = card.hasCustomCover();
        boolean hasVodPic = card.getVodPic() != null && !card.getVodPic().isEmpty();

        List<String> labels = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();

        labels.add(context.getString(R.string.elder_choose_cover));
        actions.add(ACTION_PICK_COVER);

        if (hasCustomCover) {
            labels.add(context.getString(hasVodPic ? R.string.elder_restore_pic : R.string.elder_clear_pic));
            actions.add(ACTION_RESTORE_COVER);
        }

        labels.add(context.getString(R.string.elder_move_up));
        actions.add(ACTION_MOVE_UP);

        labels.add(context.getString(R.string.elder_move_down));
        actions.add(ACTION_MOVE_DOWN);

        labels.add(context.getString(R.string.elder_remove));
        actions.add(ACTION_REMOVE);

        String[] items = labels.toArray(new String[0]);

        return new AlertDialog.Builder(context)
                .setTitle(card.getName())
                .setItems(items, (dialog, which) -> {
                    int action = actions.get(which);
                    switch (action) {
                        case ACTION_PICK_COVER: if (callback != null) callback.onPickCover(card); break;
                        case ACTION_RESTORE_COVER: clearPic(); break;
                        case ACTION_MOVE_UP: moveUp(); break;
                        case ACTION_MOVE_DOWN: moveDown(); break;
                        case ACTION_REMOVE: remove(); break;
                    }
                })
                .create();
    }

    private void moveUp() {
        List<ElderCard> cards = AppDatabase.get().getElderCardDao().findAll();
        int index = -1;
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).getId().equals(card.getId())) { index = i; break; }
        }
        if (index > 0) {
            ElderCard prev = cards.get(index - 1);
            int temp = card.getSortOrder();
            card.setSortOrder(prev.getSortOrder());
            prev.setSortOrder(temp);
            card.save();
            prev.save();
        }
        if (callback != null) callback.onCardChanged();
    }

    private void moveDown() {
        List<ElderCard> cards = AppDatabase.get().getElderCardDao().findAll();
        int index = -1;
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).getId().equals(card.getId())) { index = i; break; }
        }
        if (index >= 0 && index < cards.size() - 1) {
            ElderCard next = cards.get(index + 1);
            int temp = card.getSortOrder();
            card.setSortOrder(next.getSortOrder());
            next.setSortOrder(temp);
            card.save();
            next.save();
        }
        if (callback != null) callback.onCardChanged();
    }

    private void clearPic() {
        card.setPic(null);
        card.setCoverType(ElderCard.CoverType.DEFAULT);
        card.setCoverValue(null);
        card.save();
        if (callback != null) callback.onCardChanged();
    }

    private void remove() {
        AppDatabase.get().getElderCardDao().deleteAndReorder(card.getId());
        if (callback != null) callback.onCardChanged();
    }
}
