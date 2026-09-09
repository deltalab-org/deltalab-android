/*
 * Copyright (C) 2026 ArcaneChat contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thoughtcrime.securesms.util;

import android.content.Context;
import android.content.Intent;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import org.thoughtcrime.securesms.R;

/**
 * Custom ActionMode callback shown when the user selects part of a message's text.
 *
 * <p>It exposes "Copy", "Select all" and "Share" actions for the currently selected text,
 * and shows a confirmation toast when text is copied to the clipboard.
 */
public class TextSelectionActionModeCallback implements ActionMode.Callback {

  private final Context context;
  private final TextView textView;

  public TextSelectionActionModeCallback(@NonNull Context context, @NonNull TextView textView) {
    this.context = context;
    this.textView = textView;
  }

  @Override
  public boolean onCreateActionMode(ActionMode mode, Menu menu) {
    mode.getMenuInflater().inflate(R.menu.text_selection_context, menu);
    return true;
  }

  @Override
  public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
    return false;
  }

  @Override
  public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.menu_context_copy) {
      CharSequence selected = textView.getText().subSequence(
          textView.getSelectionStart(), textView.getSelectionEnd());
      Util.writeTextToClipboard(context, selected.toString());
      Toast.makeText(
              context,
              R.string.copied_to_clipboard,
              Toast.LENGTH_SHORT)
          .show();
      mode.finish();
      return true;
    } else if (id == R.id.menu_context_select_all) {
      textView.selectAll();
      return true;
    } else if (id == R.id.menu_context_share_text) {
      CharSequence selected = textView.getText().subSequence(
          textView.getSelectionStart(), textView.getSelectionEnd());
      shareText(selected.toString());
      mode.finish();
      return true;
    }
    return false;
  }

  @Override
  public void onDestroyActionMode(ActionMode mode) {}

  private void shareText(@NonNull String text) {
    Intent sendIntent = new Intent(Intent.ACTION_SEND);
    sendIntent.setType("text/plain");
    sendIntent.putExtra(Intent.EXTRA_TEXT, text);
    context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.share_via)));
  }
}
