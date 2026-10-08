package com.freedom.messageprint;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.provider.Telephony;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<SmsData.Message> messages = new ArrayList<>();
    private Map<String, String> names = new HashMap<>();
    private Map<String, List<SmsData.Message>> groups = new HashMap<>();
    private LinearLayout rows;
    private TextView status, title;
    private Button fromButton, toButton, printButton, backButton, smsButton, contactsButton;
    private EditText search;
    private long from = Long.MIN_VALUE, until = Long.MAX_VALUE;
    private String selected;
    private boolean loading;
    private WebView printPreview;
    private final SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA);
    private final SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            from = state.getLong("from", Long.MIN_VALUE);
            until = state.getLong("until", Long.MAX_VALUE);
            selected = state.getString("selected");
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 248, 251));
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(dp(18) + bars.left, dp(18) + bars.top, dp(18) + bars.right, dp(12) + bars.bottom);
                return insets;
            });
        }
        setContentView(root);
        title = label("문자 모아 인쇄", 25);
        root.addView(title);
        root.addView(label("연락처별 받은 문자 · 휴대폰 안에서만 처리", 13));
        LinearLayout permissions = horizontal();
        smsButton = button("문자 읽기 허용", v -> requestPermissions(new String[]{Manifest.permission.READ_SMS}, 10));
        contactsButton = button("연락처 이름 허용", v -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 11));
        permissions.addView(smsButton, weighted());
        permissions.addView(contactsButton, weighted());
        root.addView(permissions);
        LinearLayout dates = horizontal();
        fromButton = button("시작일", v -> pickDate(true));
        toButton = button("종료일", v -> pickDate(false));
        dates.addView(fromButton, weighted()); dates.addView(toButton, weighted());
        root.addView(dates);
        LinearLayout actions = horizontal();
        actions.addView(button("전체 기간", v -> { from = Long.MIN_VALUE; until = Long.MAX_VALUE; render(); }), weighted());
        actions.addView(button("새로고침", v -> load()), weighted());
        actions.addView(button("권한 설정", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName())))), weighted());
        root.addView(actions);
        search = new EditText(this);
        search.setSingleLine(true); search.setHint("이름 또는 전화번호 검색");
        search.setTextSize(15);
        root.addView(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { renderRows(); }
            public void afterTextChanged(Editable s) {}
        });
        LinearLayout selection = horizontal();
        backButton = button("← 연락처 목록", v -> { selected = null; render(); });
        printButton = button("인쇄 / PDF 저장", v -> showPrintPreview());
        selection.addView(backButton, weighted()); selection.addView(printButton, weighted());
        root.addView(selection);
        status = label("", 14); root.addView(status);
        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        render();
    }

    @Override protected void onResume() { super.onResume(); load(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong("from", from); out.putLong("until", until); out.putString("selected", selected);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (results.length > 0 && results[0] != PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "권한이 없으면 해당 정보를 읽을 수 없습니다. 권한 설정에서 변경할 수 있습니다.", Toast.LENGTH_LONG).show();
        load();
    }
    private boolean allowed(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private void load() {
        if (loading) return;
        if (!allowed(Manifest.permission.READ_SMS)) {
            messages = new ArrayList<>(); names = new HashMap<>(); render();
            status.setText("받은 문자를 표시하려면 문자 읽기 권한을 허용해 주세요. 연락처 권한은 선택 사항입니다.");
            return;
        }
        loading = true; printButton.setEnabled(false); status.setText("받은 문자를 읽고 있습니다…");
        worker.execute(() -> {
            List<SmsData.Message> found = new ArrayList<>();
            Map<String, String> contactNames = new HashMap<>();
            String error = null;
            try (Cursor cursor = getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{"address", "body", "date"}, null, null, "date DESC")) {
                if (cursor == null) throw new IllegalStateException("문자 저장소에 접근할 수 없습니다.");
                while (cursor.moveToNext()) found.add(new SmsData.Message(cursor.getString(0), cursor.getString(1), cursor.getLong(2)));
                if (allowed(Manifest.permission.READ_CONTACTS)) {
                    Set<String> addresses = new HashSet<>();
                    for (SmsData.Message message : found) {
                        String key = SmsData.key(message.address);
                        if (!addresses.add(key)) continue;
                        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(message.address));
                        try (Cursor contact = getContentResolver().query(uri,
                            new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
                            if (contact != null && contact.moveToFirst() && contact.getString(0) != null)
                                contactNames.put(key, contact.getString(0));
                        } catch (SecurityException ignored) { /* Phone numbers remain usable without contacts. */ }
                    }
                }
            } catch (Exception failure) {
                error = "문자를 읽지 못했습니다. 문자 권한과 휴대폰의 SMS 저장소를 확인해 주세요.";
            }
            String failureMessage = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                loading = false;
                messages = failureMessage == null ? found : new ArrayList<>();
                names = contactNames; render();
                if (failureMessage != null) status.setText(failureMessage);
            });
        });
    }
    private void pickDate(boolean start) {
        Calendar initial = Calendar.getInstance();
        long current = start ? from : until;
        if (current != Long.MIN_VALUE && current != Long.MAX_VALUE) initial.setTimeInMillis(start ? current : current - 1);
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar date = Calendar.getInstance(); date.clear(); date.set(year, month, dayOfMonth);
            long candidate = date.getTimeInMillis();
            if (!start) { date.add(Calendar.DAY_OF_MONTH, 1); candidate = date.getTimeInMillis(); }
            if ((start && candidate >= until) || (!start && candidate <= from)) {
                Toast.makeText(this, "종료일은 시작일보다 빠를 수 없습니다.", Toast.LENGTH_LONG).show(); return;
            }
            if (start) from = candidate; else until = candidate;
            render();
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)).show();
    }
    private String range() {
        return (from == Long.MIN_VALUE ? "전체" : day.format(new Date(from))) + " ~ "
            + (until == Long.MAX_VALUE ? "전체" : day.format(new Date(until - 1)));
    }
    private void render() {
        if (rows == null) return;
        groups = SmsData.group(messages, from, until);
        fromButton.setText(from == Long.MIN_VALUE ? "시작일: 전체" : day.format(new Date(from)));
        toButton.setText(until == Long.MAX_VALUE ? "종료일: 전체" : day.format(new Date(until - 1)));
        smsButton.setVisibility(allowed(Manifest.permission.READ_SMS) ? View.GONE : View.VISIBLE);
        contactsButton.setVisibility(allowed(Manifest.permission.READ_CONTACTS) ? View.GONE : View.VISIBLE);
        backButton.setVisibility(selected == null ? View.GONE : View.VISIBLE);
        printButton.setVisibility(selected == null ? View.GONE : View.VISIBLE);
        printButton.setEnabled(!loading && groups.containsKey(selected));
        search.setVisibility(selected == null ? View.VISIBLE : View.GONE);
        title.setText(selected == null ? "문자 모아 인쇄" : names.getOrDefault(selected, selected));
        renderRows();
    }
    private void renderRows() {
        if (rows == null) return;
        rows.removeAllViews();
        if (selected != null) {
            List<SmsData.Message> conversation = groups.getOrDefault(selected, new ArrayList<>());
            status.setText(selected + " · 받은 문자 " + conversation.size() + "건\n" + range());
            if (conversation.isEmpty()) rows.addView(label("선택한 기간에 받은 문자가 없습니다.", 16));
            for (SmsData.Message message : conversation) {
                LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(12), dp(12), dp(12)); card.setBackgroundColor(Color.WHITE);
                card.addView(label(timestamp.format(new Date(message.date)), 12));
                TextView body = label(message.body, 16); body.setTextIsSelectable(true); card.addView(body);
                LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2); space.bottomMargin = dp(10);
                rows.addView(card, space);
            }
        } else {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            int count = 0;
            for (Map.Entry<String, List<SmsData.Message>> entry : groups.entrySet()) {
                String key = entry.getKey(), name = names.getOrDefault(key, key);
                if (!name.toLowerCase(Locale.ROOT).contains(query) && !key.contains(query)
                    && !key.contains(SmsData.key(query))) continue;
                count++;
                String latest = entry.getValue().get(0).body.replace('\n', ' ');
                if (latest.length() > 70) latest = latest.substring(0, 70) + "…";
                Button row = button(name + (name.equals(key) ? "" : " · " + key) + "\n받은 문자 " + entry.getValue().size()
                    + "건 · " + timestamp.format(new Date(entry.getValue().get(0).date)) + "\n" + latest,
                    v -> { selected = key; render(); });
                row.setAllCaps(false); row.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(12), dp(12), dp(12)); rows.addView(row);
            }
            status.setText("발신자 " + count + "명 · " + range());
            if (count == 0) rows.addView(label(allowed(Manifest.permission.READ_SMS)
                ? "표시할 받은 문자가 없습니다. 기간과 검색어를 확인해 주세요.\nSMS만 지원하며 MMS·RCS·카카오톡은 포함되지 않습니다."
                : "문자 읽기 권한을 허용하면 연락처별 목록이 표시됩니다.", 16));
        }
    }
    private void showPrintPreview() {
        List<SmsData.Message> conversation = groups.get(selected);
        if (conversation == null || conversation.isEmpty()) return;
        String name = names.getOrDefault(selected, selected);
        printPreview = new WebView(this);
        printPreview.getSettings().setJavaScriptEnabled(false);
        printPreview.getSettings().setAllowFileAccess(false);
        printPreview.getSettings().setAllowContentAccess(false);
        printPreview.getSettings().setBlockNetworkLoads(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("인쇄 미리보기 · " + conversation.size() + "건")
            .setView(printPreview).setPositiveButton("인쇄 / PDF", null).setNegativeButton("닫기", null).create();
        WebView preview = printPreview;
        dialog.setOnShowListener(ignored -> {
            Button print = dialog.getButton(AlertDialog.BUTTON_POSITIVE); print.setEnabled(false);
            print.setOnClickListener(v -> {
                PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
                if (manager == null) { Toast.makeText(this, "이 기기는 인쇄 기능을 지원하지 않습니다.", Toast.LENGTH_LONG).show(); return; }
                manager.print("받은 문자 - " + name, preview.createPrintDocumentAdapter("받은 문자 - " + name),
                    new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME).build());
            });
            preview.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { print.setEnabled(true); }
            });
            preview.loadDataWithBaseURL(null, PrintDocument.html(name, selected, range(), conversation), "text/html", "UTF-8", null);
        });
        dialog.setOnDismissListener(ignored -> { preview.destroy(); if (printPreview == preview) printPreview = null; });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, (int) (getResources().getDisplayMetrics().heightPixels * .85));
    }
    @Override public void onBackPressed() {
        if (selected != null) { selected = null; render(); } else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (printPreview != null) printPreview.destroy();
        super.onDestroy();
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private TextView label(String text, int size) {
        TextView label = new TextView(this); label.setText(text); label.setTextSize(size);
        label.setTextColor(Color.rgb(27, 48, 65)); label.setPadding(0, dp(6), 0, dp(6)); return label;
    }
    private LinearLayout horizontal() { LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.HORIZONTAL); return line; }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }
    private Button button(String text, View.OnClickListener action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(13); button.setOnClickListener(action); return button;
    }
}
