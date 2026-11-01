package com.snapmaps.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.googlecode.tesseract.android.ResultIterator;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class OcrReader {
    private OcrReader() { }

    static Bitmap decode(Context context, Uri uri) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Image is unreadable.");
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 4096)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);
            if (bitmap == null) throw new IOException("Image is unreadable.");
            return bitmap;
        }
    }

    static String readBottomAddress(Context context, Bitmap screenshot) throws Exception {
        if (screenshot.getHeight() < 80 || screenshot.getWidth() < 80)
            throw new IOException("The screenshot is too small. Share the original image.");
        int top = screenshot.getHeight() / 2;
        Bitmap bottom = Bitmap.createBitmap(screenshot, 0, top, screenshot.getWidth(), screenshot.getHeight() - top);
        try {
            String result = readLatin(bottom);
            if (!result.isEmpty()) return result;
            checkInterrupted();
            File data = new File(context.getFilesDir(), "tessdata");
            if (!data.isDirectory() && !data.mkdirs()) throw new IOException("OCR storage is unavailable.");
            downloadModel(data, "eng");
            result = readTesseract(context, bottom, "eng");
            if (!result.isEmpty()) return result;
            checkInterrupted();
            downloadModel(data, "kan");
            return readTesseract(context, bottom, "eng+kan");
        } finally { bottom.recycle(); }
    }

    private static String readLatin(Bitmap bitmap) throws Exception {
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        Bitmap input = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (input == null) { recognizer.close(); throw new IOException("Could not prepare OCR image."); }
        Task<Text> task;
        try { task = recognizer.process(InputImage.fromBitmap(input, 0)); }
        catch (RuntimeException failure) { input.recycle(); recognizer.close(); throw failure; }
        // ML Kit can finish after the request is cancelled. It owns its input until completion.
        task.addOnCompleteListener(Runnable::run, completed -> { input.recycle(); recognizer.close(); });
        try {
            Text text = Tasks.await(task, 25, TimeUnit.SECONDS);
            List<AddressExtractor.Line> lines = new ArrayList<>();
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    Rect bounds = line.getBoundingBox();
                    if (bounds != null) lines.add(new AddressExtractor.Line(line.getText(),
                            bounds.left, bounds.top, bounds.right, bounds.bottom));
                }
            }
            return AddressExtractor.extract(lines, bitmap.getWidth(), bitmap.getHeight());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
            return "";
        }
    }

    private static String readTesseract(Context context, Bitmap bitmap, String languages) throws Exception {
        checkInterrupted();
        TessBaseAPI api = new TessBaseAPI();
        try {
            if (!api.init(context.getFilesDir().getAbsolutePath(), languages))
                throw new IOException("OCR model could not load.");
            api.setImage(bitmap);
            api.getUTF8Text();
            checkInterrupted();
            ResultIterator iterator = api.getResultIterator();
            if (iterator == null) return "";
            List<AddressExtractor.Line> lines = new ArrayList<>();
            int level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE;
            try {
                iterator.begin();
                do {
                    String text = iterator.getUTF8Text(level);
                    if (text == null || text.trim().isEmpty()) continue;
                    Rect bounds = iterator.getBoundingRect(level);
                    lines.add(new AddressExtractor.Line(text, bounds.left, bounds.top, bounds.right, bounds.bottom));
                } while (iterator.next(level));
            } finally { iterator.delete(); }
            return AddressExtractor.extract(lines, bitmap.getWidth(), bitmap.getHeight());
        } finally { api.recycle(); }
    }

    private static void downloadModel(File folder, String language) throws Exception {
        File model = new File(folder, language + ".traineddata");
        if (model.isFile() && model.length() > 100_000) return;
        File partial = new File(folder, language + ".traineddata.partial");
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/" + language + ".traineddata")
                .openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("OCR model download failed.");
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[16384];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    checkInterrupted();
                    output.write(buffer, 0, length);
                }
            }
            if (partial.length() <= 100_000 || !partial.renameTo(model))
                throw new IOException("OCR model download is incomplete.");
        } finally {
            connection.disconnect();
            if (partial.exists()) partial.delete();
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
}
