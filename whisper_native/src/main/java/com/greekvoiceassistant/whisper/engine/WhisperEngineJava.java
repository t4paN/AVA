package com.greekvoiceassistant.whisper.engine;

import android.content.Context;
import android.util.Log;

import com.greekvoiceassistant.whisper.utils.WaveUtil;
import com.greekvoiceassistant.whisper.utils.WhisperUtil;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class WhisperEngineJava implements WhisperEngine {
    private final String TAG = "WhisperEngineJava";
    private final WhisperUtil mWhisperUtil = new WhisperUtil();

    private final Context mContext;
    private boolean mIsInitialized = false;
    private Interpreter mInterpreter = null;
    private GpuDelegate gpuDelegate = null;

    public WhisperEngineJava(Context context) {
        mContext = context;
    }

    @Override
    public boolean isInitialized() {
        return mIsInitialized;
    }

    @Override
    public boolean initialize(String modelPath, String vocabPath, boolean multilingual) throws IOException {
        // Load model
        loadModel(modelPath);
        Log.d(TAG, "Model is loaded..." + modelPath);

        // Load filters and vocab
        boolean ret = mWhisperUtil.loadFiltersAndVocab(multilingual, vocabPath);
        if (ret) {
            mIsInitialized = true;
            Log.d(TAG, "Filters and Vocab are loaded..." + vocabPath);
        } else {
            mIsInitialized = false;
            Log.d(TAG, "Failed to load Filters and Vocab...");
        }

        return mIsInitialized;
    }

    @Override
    public void deinitialize() {
        if (mInterpreter != null) {
            mInterpreter.close();
            mInterpreter = null;
        }
        if (gpuDelegate != null) {
            try {
                gpuDelegate.close();
            } catch (Exception ignored) {}
            gpuDelegate = null;
        }
    }

    @Override
    public String transcribeFile(String wavePath) {
        Log.d(TAG, "Calculating Mel spectrogram...");
        float[] melSpectrogram = getMelSpectrogram(wavePath);
        Log.d(TAG, "Mel spectrogram is calculated...!");

        String result = runInference(melSpectrogram);
        Log.d(TAG, "Inference is executed...!");

        return result;
    }

    @Override
    public String transcribeBuffer(float[] samples) {
        Log.d(TAG, "Transcribing from PCM buffer, samples: " + samples.length);

        // Pad or trim to fixed input size (30 seconds at 16kHz)
        int fixedInputSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE;
        float[] inputSamples = new float[fixedInputSize];
        int copyLength = Math.min(samples.length, fixedInputSize);
        System.arraycopy(samples, 0, inputSamples, 0, copyLength);

        // Calculate mel spectrogram
        Log.d(TAG, "Calculating Mel spectrogram from buffer...");
        int cores = Runtime.getRuntime().availableProcessors();
        float[] melSpectrogram = mWhisperUtil.getMelSpectrogram(inputSamples, inputSamples.length, cores);
        Log.d(TAG, "Mel spectrogram calculated!");

        // Run inference
        String result = runInference(melSpectrogram);
        Log.d(TAG, "Inference complete!");

        return result;
    }

    private void loadModel(String modelPath) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(modelPath);
        FileChannel fileChannel = fileInputStream.getChannel();
        long startOffset = 0;
        long declaredLength = fileChannel.size();
        ByteBuffer tfliteModel = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(6);

        boolean gpuSuccess = false;

        // Try GPU delegate first
        try {

            gpuDelegate = new GpuDelegate();

            Interpreter.Options gpuTestOptions = new Interpreter.Options();
            gpuTestOptions.setNumThreads(6);
            gpuTestOptions.addDelegate(gpuDelegate);

            // Test if interpreter actually initializes with GPU
            Interpreter testInterpreter = new Interpreter(tfliteModel, gpuTestOptions);
            testInterpreter.close();

            // If we got here, GPU works - use it for real
            options.addDelegate(gpuDelegate);
            gpuSuccess = true;
            Log.d(TAG, "✓ GPU Delegate enabled");

        } catch (Exception e) {
            Log.w(TAG, "✗ GPU Delegate failed: " + e.getMessage());
            if (gpuDelegate != null) {
                try {
                    gpuDelegate.close();
                } catch (Exception ignored) {}
                gpuDelegate = null;
            }
        }

        // Fallback to XNNPACK if GPU didn't work
        if (!gpuSuccess) {
            Log.d(TAG, "Using XNNPACK fallback");
            options.setUseXNNPACK(true);
        }

        mInterpreter = new Interpreter(tfliteModel, options);
    }

    private float[] getMelSpectrogram(String wavePath) {
        float[] samples = WaveUtil.getSamples(wavePath);

        int fixedInputSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE;
        float[] inputSamples = new float[fixedInputSize];
        int copyLength = Math.min(samples.length, fixedInputSize);
        System.arraycopy(samples, 0, inputSamples, 0, copyLength);

        int cores = Runtime.getRuntime().availableProcessors();
        return mWhisperUtil.getMelSpectrogram(inputSamples, inputSamples.length, cores);
    }

    private String runInference(float[] inputData) {
        // Check for available signatures
        String[] signatures = mInterpreter.getSignatureKeys();
        Log.d(TAG, "Available signatures: " + Arrays.toString(signatures));

        String signature_key = "serving_default";

        // Try to use Greek language forcing if available
        if (Arrays.asList(signatures).contains("serving_transcribe_lang")) {
            signature_key = "serving_transcribe_lang";
            Log.d(TAG, "Using serving_transcribe_lang signature with Greek token");
        }

        // Create output tensor
        Tensor outputTensor = mInterpreter.getOutputTensor(0);
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), DataType.FLOAT32);

        // Prepare mel spectrogram input
        Tensor inputTensor = mInterpreter.getInputTensor(0);
        int inputSize = inputTensor.shape()[0] * inputTensor.shape()[1] * inputTensor.shape()[2] * Float.BYTES;
        ByteBuffer inputBuf = ByteBuffer.allocateDirect(inputSize);
        inputBuf.order(ByteOrder.nativeOrder());
        for (float input : inputData) {
            inputBuf.putFloat(input);
        }

        // Use signature-based inference if available
        if (signature_key.equals("serving_transcribe_lang")) {
            Map<String, Object> inputsMap = new HashMap<>();
            String[] inputs = mInterpreter.getSignatureInputs(signature_key);
            inputsMap.put(inputs[0], inputBuf);

            // Add Greek language token (50281)
            IntBuffer langTokenBuffer = IntBuffer.allocate(1);
            langTokenBuffer.put(50281);  // Greek
            langTokenBuffer.rewind();
            inputsMap.put(inputs[1], langTokenBuffer);

            Log.d(TAG, "Running inference with Greek language token (50281)");

            Map<String, Object> outputsMap = new HashMap<>();
            String[] outputs = mInterpreter.getSignatureOutputs(signature_key);
            outputsMap.put(outputs[0], outputBuffer.getBuffer());

            // Run with signature
            mInterpreter.runSignature(inputsMap, outputsMap, signature_key);
        } else {
            // Fallback to basic inference
            Log.d(TAG, "Using basic inference (no signature support)");
            TensorBuffer inputBuffer = TensorBuffer.createFixedSize(inputTensor.shape(), inputTensor.dataType());
            inputBuffer.loadBuffer(inputBuf);
            mInterpreter.run(inputBuffer.getBuffer(), outputBuffer.getBuffer());
        }

        // Retrieve the results
        int outputLen = outputBuffer.getIntArray().length;
        Log.d(TAG, "output_len: " + outputLen);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < outputLen; i++) {
            int token = outputBuffer.getBuffer().getInt();
            if (token == mWhisperUtil.getTokenEOT())
                break;

            if (token < mWhisperUtil.getTokenEOT()) {
                String word = mWhisperUtil.getWordFromToken(token);
                result.append(word);
            } else {
                if (token == mWhisperUtil.getTokenTranscribe())
                    Log.d(TAG, "It is Transcription...");
                if (token == mWhisperUtil.getTokenTranslate())
                    Log.d(TAG, "It is Translation...");

                String word = mWhisperUtil.getWordFromToken(token);
                Log.d(TAG, "Skipping token: " + token + ", word: " + word);
            }
        }

        return result.toString();
    }
}