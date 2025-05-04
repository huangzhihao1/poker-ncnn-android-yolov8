// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include <android/log.h>

#include <jni.h>

#include <string>
#include <vector>

#include <platform.h>
#include <benchmark.h>

#include "yolo.h"

#include "ndkcamera.h"

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#if __ARM_NEON
#include <arm_neon.h>
#endif // __ARM_NEON

static int draw_unsupported(cv::Mat& rgb)
{
    const char text[] = "unsupported";

    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 1.0, 1, &baseLine);

    int y = (rgb.rows - label_size.height) / 2;
    int x = (rgb.cols - label_size.width) / 2;

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);

    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 0, 0));

    return 0;
}

static int draw_fps(cv::Mat& rgb)
{
    // resolve moving average
    float avg_fps = 0.f;
    {
        static double t0 = 0.f;
        static float fps_history[10] = {0.f};

        double t1 = ncnn::get_current_time();
        if (t0 == 0.f)
        {
            t0 = t1;
            return 0;
        }

        float fps = 1000.f / (t1 - t0);
        t0 = t1;

        for (int i = 9; i >= 1; i--)
        {
            fps_history[i] = fps_history[i - 1];
        }
        fps_history[0] = fps;

        if (fps_history[9] == 0.f)
        {
            return 0;
        }

        for (int i = 0; i < 10; i++)
        {
            avg_fps += fps_history[i];
        }
        avg_fps /= 10.f;
    }

    char text[32];
    sprintf(text, "FPS=%.2f", avg_fps);

    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 0.5, 1, &baseLine);

    int y = 0;
    int x = rgb.cols - label_size.width;

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);

    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 0.5, cv::Scalar(0, 0, 0));

    return 0;
}

static Yolo* g_yolo = 0;
static ncnn::Mutex lock;

// 扑克牌类别名称数组
static const char* class_names[] = {
    "梅花10", "方块10", "红桃10", "黑桃10", "梅花2", "方块2", "红桃2", "黑桃2", "梅花3", "方块3", 
    "红桃3", "黑桃3", "梅花4", "方块4", "红桃4", "黑桃4", "梅花5", "方块5", "红桃5", "黑桃5", 
    "梅花6", "方块6", "红桃6", "黑桃6", "梅花7", "方块7", "红桃7", "黑桃7", "梅花8", "方块8", 
    "红桃8", "黑桃8", "梅花9", "方块9", "红桃9", "黑桃9", "梅花A", "方块A", "红桃A", "黑桃A", 
    "梅花J", "方块J", "红桃J", "黑桃J", "梅花K", "方块K", "红桃K", "黑桃K", "梅花Q", "方块Q", 
    "红桃Q", "黑桃Q"
};

class MyNdkCamera : public NdkCameraWindow
{
public:
    virtual void on_image_render(cv::Mat& rgb) const;
    void set_java_vm(JavaVM* vm) { java_vm = vm; }

private:
    JavaVM* java_vm;
};

static MyNdkCamera* g_camera = 0;

// 修改JNI回调方法，去除saveErrorLog相关内容
static void onDetectResult(JNIEnv* env, jobject thiz, const char* cardName, float confidence) {
    jclass cls = env->GetObjectClass(thiz);
    // 只回调onDetectResult，不再调用saveErrorLog
    jmethodID methodID = env->GetMethodID(cls, "onDetectResult", "(Ljava/lang/String;F)V");
    if (methodID != NULL) {
        jstring jCardName = env->NewStringUTF(cardName);
        env->CallVoidMethod(thiz, methodID, jCardName, confidence);
        env->DeleteLocalRef(jCardName);
    }
}

void MyNdkCamera::on_image_render(cv::Mat& rgb) const
{
    // nanodet
    {
        ncnn::MutexLockGuard g(lock);

        if (g_yolo)
        {
            std::vector<Object> objects;
            __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "Starting detection on image %dx%d", rgb.cols, rgb.rows);
            int ret = g_yolo->detect(rgb, objects);
            __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "Detection completed with result %d, found %d objects", ret, (int)objects.size());

            if (ret == 0 && !objects.empty())
            {
                g_yolo->draw(rgb, objects);
                __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "Drawing completed");

                // 获取JNI环境
                JNIEnv* env = NULL;
                JavaVM* vm = java_vm;
                if (vm->GetEnv((void**)&env, JNI_VERSION_1_4) == JNI_OK) {
                    jobject activity = g_camera->getActivity();
                    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "JNI: activity=%p, objects.size=%d", activity, (int)objects.size());
                    if (activity != NULL && !objects.empty()) {
                        // 获取当前时间戳
                        long long timestamp = ncnn::get_current_time();
                        // 只回调一次onDetectResult，只保存当前画面显示的第一个目标（置信度最高的目标）
                        const Object& obj = objects[0];
                            const char* cardName = class_names[obj.label];
                        // 添加时间戳参数
                        jmethodID methodID = env->GetMethodID(env->GetObjectClass(activity), "onDetectResult", "(Ljava/lang/String;FJ)V");
                        if (methodID != NULL) {
                            jstring jCardName = env->NewStringUTF(cardName);
                            env->CallVoidMethod(activity, methodID, jCardName, obj.prob, timestamp);
                            env->DeleteLocalRef(jCardName);
                        }
                    } else {
                        __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "JNI: activity is NULL, cannot callback onDetectResult");
                    }
                }
            }
        }
        else
        {
            __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "Yolo model not initialized");
            draw_unsupported(rgb);
        }
    }

    draw_fps(rgb);
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "Yolov8Ncnn", "JNI_OnLoad");

    ncnn::create_gpu_instance();

    if (g_yolo == 0)
    {
        g_yolo = new Yolo;
    }

    if (g_camera == 0)
    {
        g_camera = new MyNdkCamera;
        g_camera->set_java_vm(vm);
    }

    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "Yolov8Ncnn", "JNI_OnUnload");

    {
        ncnn::MutexLockGuard g(lock);

        if (g_yolo)
        {
            delete g_yolo;
            g_yolo = 0;
        }
    }

    if (g_camera)
    {
        JNIEnv* env = nullptr;
        if (vm->GetEnv((void**)&env, JNI_VERSION_1_4) == JNI_OK && g_camera->getActivity()) {
            env->DeleteGlobalRef(g_camera->getActivity());
        }
        g_camera->close();
        delete g_camera;
        g_camera = 0;
    }

    ncnn::destroy_gpu_instance();
}

// public native boolean loadModel(AssetManager mgr, int modelid, int cpugpu);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolov8ncnn_Yolov8Ncnn_loadModel(JNIEnv* env, jobject thiz, jobject assetManager, jint modelid, jint cpugpu)
{
    if (modelid < 0 || modelid > 6 || cpugpu < 0 || cpugpu > 1)
    {
        __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Invalid modelid %d or cpugpu %d", modelid, cpugpu);
        return JNI_FALSE;
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr)
    {
        __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Failed to get asset manager");
        return JNI_FALSE;
    }

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "loadModel %p", mgr);

    // 只保留 best 模型
    const char* modeltypes[] =
    {
        "best"
    };

    const int target_sizes[] =
    {
        320
    };

    const float mean_vals[][3] =
    {
        {103.53f, 116.28f, 123.675f}
    };

    const float norm_vals[][3] =
    {
        { 1 / 255.f, 1 / 255.f, 1 / 255.f }
    };

    const char* modeltype = modeltypes[0];
    int target_size = target_sizes[0];
    bool use_gpu = (int)cpugpu == 1;

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "Loading model type: %s, target_size: %d, use_gpu: %d", modeltype, target_size, use_gpu);

    // reload
    {
        ncnn::MutexLockGuard g(lock);

        if (use_gpu && ncnn::get_gpu_count() == 0)
        {
            __android_log_print(ANDROID_LOG_ERROR, "ncnn", "GPU not available");
            delete g_yolo;
            g_yolo = 0;
            return JNI_FALSE;
        }

        if (!g_yolo)
        {
            g_yolo = new Yolo;
            if (!g_yolo)
            {
                __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Failed to create Yolo instance");
                return JNI_FALSE;
            }
        }

        int ret = g_yolo->load(mgr, modeltype, target_size, mean_vals[0], norm_vals[0], use_gpu);
        if (ret != 0)
        {
            __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Failed to load model: %d", ret);
            delete g_yolo;
            g_yolo = 0;
            return JNI_FALSE;
        }

        __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "Model loaded successfully");
    }

    return JNI_TRUE;
}

// public native boolean openCamera(int facing);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolov8ncnn_Yolov8Ncnn_openCamera(JNIEnv* env, jobject thiz, jint facing)
{
    if (facing < 0 || facing > 1)
        return JNI_FALSE;

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "openCamera %d", facing);

    g_camera->open((int)facing);

    return JNI_TRUE;
}

// public native boolean closeCamera();
JNIEXPORT jboolean JNICALL Java_com_tencent_yolov8ncnn_Yolov8Ncnn_closeCamera(JNIEnv* env, jobject thiz)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "closeCamera");

    g_camera->close();

    return JNI_TRUE;
}

// public native boolean setOutputWindow(Surface surface);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolov8ncnn_Yolov8Ncnn_setOutputWindow(JNIEnv* env, jobject thiz, jobject surface)
{
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "setOutputWindow %p", win);

    g_camera->set_window(win);

    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_tencent_yolov8ncnn_Yolov8Ncnn_setActivity(JNIEnv* env, jobject thiz, jobject activity)
{
    if (g_camera)
    {
        g_camera->set_activity(env, activity);
        __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "setActivity: activity=%p", activity);
    }
}

}
