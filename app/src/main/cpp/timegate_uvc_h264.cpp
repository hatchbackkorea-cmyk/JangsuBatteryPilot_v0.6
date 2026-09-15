#include <jni.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr const char* TAG = "TimeGateUvcH264";
constexpr size_t MAX_FRAME_BYTES = 8u * 1024u * 1024u;
constexpr int URB_COUNT = 8;

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct Session;

struct UrbHolder {
    usbdevfs_urb* urb = nullptr;
    uint8_t* buffer = nullptr;
    int packetSize = 0;
    int packets = 0;
    int transferBytes = 0;
};

struct Session {
    JavaVM* vm = nullptr;
    jobject listener = nullptr;
    jmethodID onFrame = nullptr;
    jmethodID onState = nullptr;
    int fd = -1;
    int endpoint = 0;
    int endpointType = 0;
    int packetSize = 0;
    int transferBytes = 0;
    int packetsPerUrb = 0;
    std::atomic<bool> stopping{false};
    std::thread worker;
    std::vector<std::unique_ptr<UrbHolder>> urbs;
    std::vector<uint8_t> frame;
    int currentFid = -1;
    uint32_t sequence = 0;
};

int64_t monotonicUs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000LL + ts.tv_nsec / 1000LL;
}

JNIEnv* envFor(Session* s, bool* attached) {
    *attached = false;
    JNIEnv* env = nullptr;
    if (s->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (s->vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        *attached = true;
        return env;
    }
    return nullptr;
}

void state(Session* s, int code, const std::string& message) {
    if (!s || !s->listener || !s->onState) return;
    bool attached = false;
    JNIEnv* env = envFor(s, &attached);
    if (!env) return;
    jstring text = env->NewStringUTF(message.c_str());
    env->CallVoidMethod(s->listener, s->onState, static_cast<jint>(code), text);
    env->DeleteLocalRef(text);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) s->vm->DetachCurrentThread();
}

void emitFrame(Session* s) {
    if (!s || s->frame.empty() || s->frame.size() > MAX_FRAME_BYTES) {
        if (s) s->frame.clear();
        return;
    }
    bool attached = false;
    JNIEnv* env = envFor(s, &attached);
    if (!env) {
        s->frame.clear();
        return;
    }
    jbyteArray data = env->NewByteArray(static_cast<jsize>(s->frame.size()));
    if (data) {
        env->SetByteArrayRegion(
            data,
            0,
            static_cast<jsize>(s->frame.size()),
            reinterpret_cast<const jbyte*>(s->frame.data())
        );
        env->CallVoidMethod(
            s->listener,
            s->onFrame,
            data,
            static_cast<jlong>(monotonicUs()),
            static_cast<jint>(s->sequence++)
        );
        env->DeleteLocalRef(data);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (attached) s->vm->DetachCurrentThread();
    s->frame.clear();
}

void feedPayload(Session* s, const uint8_t* data, int length) {
    if (!s || !data || length < 2) return;
    const int headerLength = static_cast<int>(data[0]);
    const uint8_t flags = data[1];
    if (headerLength < 2 || headerLength > length) return;
    if ((flags & 0x40u) != 0u) {
        s->frame.clear();
        s->currentFid = -1;
        return;
    }

    const int fid = flags & 0x01u;
    const bool eof = (flags & 0x02u) != 0u;
    if (s->currentFid < 0) s->currentFid = fid;
    if (fid != s->currentFid) {
        if (!s->frame.empty()) emitFrame(s);
        s->currentFid = fid;
    }

    const int payloadBytes = length - headerLength;
    if (payloadBytes > 0) {
        if (s->frame.size() + static_cast<size_t>(payloadBytes) > MAX_FRAME_BYTES) {
            s->frame.clear();
            s->currentFid = -1;
            return;
        }
        s->frame.insert(s->frame.end(), data + headerLength, data + length);
    }

    if (eof) {
        emitFrame(s);
        s->currentFid = -1;
    }
}

std::unique_ptr<UrbHolder> makeIsoUrb(Session* s) {
    auto holder = std::make_unique<UrbHolder>();
    holder->packetSize = s->packetSize;
    holder->packets = s->packetsPerUrb;
    holder->transferBytes = holder->packetSize * holder->packets;
    const size_t urbBytes = sizeof(usbdevfs_urb) +
        static_cast<size_t>(holder->packets) * sizeof(usbdevfs_iso_packet_desc);
    holder->urb = static_cast<usbdevfs_urb*>(std::calloc(1, urbBytes));
    holder->buffer = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(holder->transferBytes)));
    if (!holder->urb || !holder->buffer) return nullptr;

    auto* urb = holder->urb;
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = static_cast<unsigned char>(s->endpoint);
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = holder->buffer;
    urb->buffer_length = holder->transferBytes;
    urb->number_of_packets = holder->packets;
    urb->usercontext = holder.get();
    for (int i = 0; i < holder->packets; ++i) {
        urb->iso_frame_desc[i].length = holder->packetSize;
    }
    return holder;
}

std::unique_ptr<UrbHolder> makeBulkUrb(Session* s) {
    auto holder = std::make_unique<UrbHolder>();
    holder->transferBytes = s->transferBytes;
    holder->urb = static_cast<usbdevfs_urb*>(std::calloc(1, sizeof(usbdevfs_urb)));
    holder->buffer = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(holder->transferBytes)));
    if (!holder->urb || !holder->buffer) return nullptr;

    auto* urb = holder->urb;
    urb->type = USBDEVFS_URB_TYPE_BULK;
    urb->endpoint = static_cast<unsigned char>(s->endpoint);
    urb->buffer = holder->buffer;
    urb->buffer_length = holder->transferBytes;
    urb->usercontext = holder.get();
    return holder;
}

bool submit(Session* s, UrbHolder* holder) {
    if (!s || !holder || !holder->urb || s->fd < 0) return false;
    holder->urb->status = 0;
    holder->urb->actual_length = 0;
    holder->urb->error_count = 0;
    if (holder->urb->type == USBDEVFS_URB_TYPE_ISO) {
        for (int i = 0; i < holder->packets; ++i) {
            holder->urb->iso_frame_desc[i].actual_length = 0;
            holder->urb->iso_frame_desc[i].status = 0;
        }
    }
    return ioctl(s->fd, USBDEVFS_SUBMITURB, holder->urb) == 0;
}

void processCompleted(Session* s, UrbHolder* holder) {
    if (!s || !holder || !holder->urb) return;
    auto* urb = holder->urb;
    if (urb->status != 0 && urb->status != -EXDEV) return;

    if (urb->type == USBDEVFS_URB_TYPE_ISO) {
        int offset = 0;
        for (int i = 0; i < holder->packets; ++i) {
            const auto& packet = urb->iso_frame_desc[i];
            if (packet.status == 0 && packet.actual_length > 0 &&
                offset + static_cast<int>(packet.actual_length) <= holder->transferBytes) {
                feedPayload(s, holder->buffer + offset, static_cast<int>(packet.actual_length));
            }
            offset += static_cast<int>(packet.length);
            if (offset > holder->transferBytes) break;
        }
    } else if (urb->actual_length > 0 && urb->actual_length <= holder->transferBytes) {
        feedPayload(s, holder->buffer, urb->actual_length);
    }
}

void freeUrbs(Session* s) {
    if (!s) return;
    for (auto& holder : s->urbs) {
        if (!holder) continue;
        if (holder->urb && s->fd >= 0) ioctl(s->fd, USBDEVFS_DISCARDURB, holder->urb);
    }
    for (auto& holder : s->urbs) {
        if (!holder) continue;
        std::free(holder->buffer);
        std::free(holder->urb);
        holder->buffer = nullptr;
        holder->urb = nullptr;
    }
    s->urbs.clear();
}

void run(Session* s) {
    const bool iso = s->endpointType == 1;
    const bool bulk = s->endpointType == 2;
    if (!iso && !bulk) {
        state(s, -2, "지원되지 않는 USB endpoint 형식");
        return;
    }

    s->urbs.reserve(URB_COUNT);
    for (int i = 0; i < URB_COUNT; ++i) {
        auto holder = iso ? makeIsoUrb(s) : makeBulkUrb(s);
        if (!holder) {
            state(s, -3, "USB URB 메모리 준비 실패");
            freeUrbs(s);
            return;
        }
        s->urbs.push_back(std::move(holder));
    }

    int submitted = 0;
    for (auto& holder : s->urbs) {
        if (submit(s, holder.get())) ++submitted;
    }
    if (submitted == 0) {
        state(s, -4, std::string("USB 스트림 시작 실패 errno=") + std::to_string(errno));
        freeUrbs(s);
        return;
    }

    state(s, 1, iso ? "UVC H264 ISO 수신 시작" : "UVC H264 BULK 수신 시작");
    while (!s->stopping.load(std::memory_order_relaxed)) {
        usbdevfs_urb* done = nullptr;
        const int rc = ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &done);
        if (rc != 0) {
            if (errno == EAGAIN || errno == EINTR) {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
                continue;
            }
            state(s, -5, std::string("USB 스트림 중단 errno=") + std::to_string(errno));
            break;
        }
        if (!done) continue;
        auto* holder = static_cast<UrbHolder*>(done->usercontext);
        processCompleted(s, holder);
        if (!s->stopping.load(std::memory_order_relaxed) && !submit(s, holder)) {
            state(s, -6, std::string("USB URB 재전송 실패 errno=") + std::to_string(errno));
            break;
        }
    }

    freeUrbs(s);
    s->frame.clear();
    state(s, 0, "UVC H264 수신 종료");
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_seungjae_jangsu280battery_UsbH264NativeReader_nativeStart(
    JNIEnv* env,
    jobject,
    jint fd,
    jint endpoint,
    jint endpointType,
    jint packetSize,
    jint transferBytes,
    jint packetsPerUrb,
    jobject listener
) {
    if (fd < 0 || !listener) return 0;
    auto* s = new Session();
    env->GetJavaVM(&s->vm);
    s->listener = env->NewGlobalRef(listener);
    jclass cls = env->GetObjectClass(listener);
    s->onFrame = env->GetMethodID(cls, "onNativeFrame", "([BJI)V");
    s->onState = env->GetMethodID(cls, "onNativeState", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(cls);
    if (!s->onFrame || !s->onState) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteGlobalRef(s->listener);
        delete s;
        return 0;
    }

    s->fd = dup(fd);
    if (s->fd < 0) {
        env->DeleteGlobalRef(s->listener);
        delete s;
        return 0;
    }
    s->endpoint = endpoint & 0xff;
    s->endpointType = endpointType;
    s->packetSize = packetSize > 0 ? packetSize : 1024;
    s->transferBytes = transferBytes > 0 ? transferBytes : 65536;
    if (s->transferBytes < 1024) s->transferBytes = 1024;
    if (s->transferBytes > 1024 * 1024) s->transferBytes = 1024 * 1024;
    s->packetsPerUrb = packetsPerUrb > 0 ? packetsPerUrb : 32;
    if (s->packetsPerUrb > 128) s->packetsPerUrb = 128;

    s->worker = std::thread([s]() { run(s); });
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_seungjae_jangsu280battery_UsbH264NativeReader_nativeStop(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    s->stopping.store(true, std::memory_order_relaxed);
    if (s->worker.joinable()) s->worker.join();
    if (s->fd >= 0) {
        close(s->fd);
        s->fd = -1;
    }
    if (s->listener) {
        env->DeleteGlobalRef(s->listener);
        s->listener = nullptr;
    }
    delete s;
}
