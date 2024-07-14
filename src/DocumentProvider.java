package com.nemo.documentProvider;

import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsProvider;
import android.database.Cursor;
import android.database.MatrixCursor.RowBuilder;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsContract.Document;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class DocumentProvider extends DocumentsProvider {
    private HashMap<String, File> idPath;

    private static class RootInfo {
        public String id;
        public int flags;
        public String title;
        public String docId;
    }

    private ArrayList<RootInfo> rootList;
    private HashMap<String, RootInfo> idToRoot;
    private HashMap<String, File> idToPath;


    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_DOCUMENT_ID
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
    };

    private String getDocIdForFile(File file) throws FileNotFoundException {
        String path = file.getAbsolutePath();
        Map.Entry<String, File> mostSpecific = null;
        for (Map.Entry<String, File> root : idToPath.entrySet()){
            final String rootPath = root.getValue().getPath();
            if (path.startsWith(rootPath) && (mostSpecific == null || rootPath.length() > mostSpecific.getValue().getPath().length())){
                mostSpecific = root;
            }
        }

        if (mostSpecific == null){
            throw new FileNotFoundException("No root contains " + path);
        }

        final String rootPath = mostSpecific.getValue().getPath();
        if (rootPath.equals(path)){
            path = "";
        } else if (rootPath.endsWith("/")){
            path = path.substring(rootPath.length());
        } else {
            path = path.substring(rootPath.length() + 1);
        }

        return mostSpecific.getKey() + ":" + path;
    }

    private File getFileForDocId(String docId) throws FileNotFoundException {
        final int splitIndex = docId.indexOf(":", 1);
        final String tag = docId.substring(0, splitIndex);
        final String path = docId.substring(splitIndex + 1);

        File target = idToPath.get(tag);
        if (target == null){
            throw new FileNotFoundException("No root for " + tag);
        }
        if (!target.exists()){
            target.mkdirs();
        }
        target = new File(target, path);
        if (!target.exists()){
            throw new FileNotFoundException("Missing file for " + docId + " at " + target);
        }
        return target;
    }

    private static String getTypeForFile(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        } else {
            return getTypeForName(file.getName());
        }
    }
    private static String getTypeForName(String name) {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1);
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }
    private void includeFile(MatrixCursor result, String docId, File file) throws FileNotFoundException{
        if (docId == null){
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        int flags = 0;
        if (file.canWrite()){
            if (file.isDirectory()){
                flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
            } else {
                flags |= Document.FLAG_SUPPORTS_WRITE;
            }
            flags |= Document.FLAG_SUPPORTS_DELETE;
        }

        final String displayName = file.getName();
        final RowBuilder row = result.newRow();
        final String mimeType = getTypeForFile(file);

        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_FLAGS, flags);

        long lastModified = file.lastModified();
        if (lastModified > 31536000000L){
            row.add(Document.COLUMN_LAST_MODIFIED, lastModified);
        }
    }

    private static String[] resolveRootProjection(String[] projection){
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    };

    private static String[] resolveDocumentProjection(String[] projection){
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    @Override
    public boolean onCreate() {
        final File rootDirectory = getContext().getExternalFilesDir(null);
        Log.i("DocumentProvider-Nemo", rootDirectory.getAbsolutePath());
        idToRoot = new HashMap<String, RootInfo>();
        idToPath = new HashMap<String, File>();
        File config = new File(getContext().getFilesDir(), "DocumentProvider.json");
        if (!config.exists()){
            return true;
        }
        return true;
    };

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException{
        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        final MatrixCursor.RowBuilder row = result.newRow();
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection((projection)));
        final File parent = getFileForDocId(parentDocumentId);
        for (File file: parent.listFiles()){
            includeFile(result, null, file);
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        Log.v("DocumentProvider-Nemo", "openDocument, mode: "+mode + ", docId: " + documentId);
        final File file = getFileForDocId(documentId);
        final int accessMode = ParcelFileDescriptor.parseMode(mode);
        final boolean isWrite = (mode.indexOf('w') != -1);

        if (isWrite){
            try {
                Handler handler = new Handler(getContext().getMainLooper());
                return ParcelFileDescriptor.open(file, accessMode, handler, new ParcelFileDescriptor.OnCloseListener() {
                    @Override
                    public void onClose(IOException e) {
                        Log.i("DocumentProvider-Nemo", "Closed File " + documentId);
                    }
                });
            } catch (IOException e){
                throw new FileNotFoundException("Failed to open id: "+documentId + ", mode: "+mode);
            }
        } else {
            return ParcelFileDescriptor.open(file, accessMode);
        }
    }
}