package com.mcool.player;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FilePickerDialog {

    private Context context;
    private File currentDir;
    private List<File> fileList;
    private OnFileSelectListener listener;
    private AlertDialog dialog;
    private ArrayAdapter<File> adapter;

    public interface OnFileSelectListener {
        void onFileSelected(File file);
    }

    public FilePickerDialog(Context context, OnFileSelectListener listener) {
        this.context = context;
        this.listener = listener;
        
        // Try to find a good starting directory
        // Check common USB mount points first
        String[] usbPaths = {
            "/storage/udisk",
            "/storage/usb0",
            "/storage/usb1",
            "/storage/usbdisk",
            "/mnt/usb_storage",
            "/mnt/usbhost",
            "/mnt/usbhost1",
            "/mnt/udisk",
            "/mnt/media_rw/udisk",
            "/mnt/media_rw/usb_storage",
            "/storage",
            "/"
        };
        
        this.currentDir = null;
        for (String path : usbPaths) {
            File dir = new File(path);
            if (dir.exists() && dir.canRead()) {
                this.currentDir = dir;
                android.util.Log.d("FilePickerDialog", "使用起始目录: " + path);
                break;
            }
        }
        
        // Fallback to root if nothing found
        if (this.currentDir == null) {
            this.currentDir = new File("/");
            android.util.Log.w("FilePickerDialog", "使用默认根目录: /");
        }
    }

    public void show() {
        loadFileList(currentDir);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(currentDir.getAbsolutePath());
        
        adapter = new ArrayAdapter<File>(context, android.R.layout.select_dialog_item, fileList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = (TextView) view.findViewById(android.R.id.text1);
                File file = getItem(position);
                if (file.isDirectory()) {
                    tv.setText(file.getName() + "/");
                } else {
                    tv.setText(file.getName());
                }
                return view;
            }
        };

        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                File selected = fileList.get(which);
                if (selected.isDirectory()) {
                    currentDir = selected; // Update current directory!
                    dialog.dismiss();
                    show(); 
                }
            }
        });

        // Add a "Select Current Folder" button
        builder.setPositiveButton("Select This Folder", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (listener != null) {
                    listener.onFileSelected(currentDir);
                }
            }
        });

        // Add "Up" button
        builder.setNeutralButton("Up", null); // We override listener to prevent dismiss

        builder.setNegativeButton("Cancel", null);

        dialog = builder.create();
        dialog.show();
        
        // Override Neutral button to prevent dismiss
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentDir.getParentFile() != null) {
                    currentDir = currentDir.getParentFile();
                    dialog.dismiss();
                    show();
                }
            }
        });
    }

    private void loadFileList(File dir) {
        fileList = new ArrayList<>();
        
        android.util.Log.d("FilePickerDialog", "尝试加载目录: " + dir.getAbsolutePath());
        
        if (!dir.exists()) {
            String msg = "Directory does not exist: " + dir.getAbsolutePath();
            android.util.Log.e("FilePickerDialog", msg);
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!dir.canRead()) {
            String msg = "Cannot read directory: " + dir.getAbsolutePath();
            android.util.Log.e("FilePickerDialog", msg);
            
            // 尝试使用 Runtime.exec 读取
            try {
                android.util.Log.d("FilePickerDialog", "尝试使用 ls 命令读取...");
                Process process = Runtime.getRuntime().exec("ls -la " + dir.getAbsolutePath());
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
                String line;
                StringBuilder output = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    android.util.Log.d("FilePickerDialog", "ls: " + line);
                }
                reader.close();
                
                if (output.length() > 0) {
                    Toast.makeText(context, "Found files via ls, but no Java read permission", Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                android.util.Log.e("FilePickerDialog", "ls 命令也失败: " + e.getMessage());
            }
            
            Toast.makeText(context, msg + "\n请尝试其他目录", Toast.LENGTH_LONG).show();
            return;
        }
        
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null && files.length > 0) {
                android.util.Log.d("FilePickerDialog", "找到 " + files.length + " 个文件/目录");
                for (File f : files) {
                    if (f.isDirectory()) {
                        if (!f.getName().equals(".") && !f.getName().equals("..")) {
                            fileList.add(f);
                            android.util.Log.d("FilePickerDialog", "添加目录: " + f.getName());
                        }
                    }
                }
            } else {
                String msg = "Empty directory or no permission: " + dir.getAbsolutePath();
                android.util.Log.w("FilePickerDialog", msg);
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            }
        }
        
        // Sort directories alphabetically
        Collections.sort(fileList, new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                return o1.getName().compareToIgnoreCase(o2.getName());
            }
        });
        
        android.util.Log.d("FilePickerDialog", "最终列表包含 " + fileList.size() + " 个目录");
    }
}
