/*
 * Can the container's Vulkan driver hand out a frame as a dma-buf?
 *
 * Creates a linear 1280x720 image whose memory is exportable as a dma-buf, exports it with vkGetMemoryFdKHR and says
 * what came out: the fd's kind, a mapping of it, and which of the external-memory extensions the device has. It is the
 * first thing to know before the X server is taught to take dma-bufs from a client (DRI3).
 *
 *   vkdmabuf
 *
 * MIT licence, written for this project.
 */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define W 1280
#define H 720

static PFN_vkGetInstanceProcAddr gipa;

#define STEP(call) do { VkResult r_ = (call); if (r_ != VK_SUCCESS) { printf("FAIL %s -> %d\n", #call, r_); return 1; } } while (0)

static int has(VkExtensionProperties *list, uint32_t n, const char *name) {
    for (uint32_t i = 0; i < n; i++) if (!strcmp(list[i].extensionName, name)) return 1;
    return 0;
}

int main(void) {
    void *lib = dlopen("libvulkan.so.1", RTLD_NOW);
    if (!lib) { printf("no libvulkan.so.1: %s\n", dlerror()); return 1; }
    gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");

    VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .apiVersion = VK_API_VERSION_1_1 };
    /* VKDMABUF_XCB=1 asks for the window-system extensions a presenting program asks for. */
    const char *surfaceExts[] = { "VK_KHR_surface", "VK_KHR_xcb_surface" };
    VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app };
    if (getenv("VKDMABUF_XCB")) { ici.enabledExtensionCount = 2; ici.ppEnabledExtensionNames = surfaceExts; }
    VkInstance inst;
    PFN_vkCreateInstance createInstance = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
    STEP(createInstance(&ici, NULL, &inst));

#define IFN(name) PFN_##name name = (PFN_##name)gipa(inst, #name)
    IFN(vkEnumeratePhysicalDevices); IFN(vkGetPhysicalDeviceProperties); IFN(vkEnumerateDeviceExtensionProperties);
    IFN(vkCreateDevice); IFN(vkGetDeviceProcAddr);
    IFN(vkGetPhysicalDeviceImageFormatProperties2);

    uint32_t count = 1;
    VkPhysicalDevice phys;
    vkEnumeratePhysicalDevices(inst, &count, &phys);
    if (!count) { printf("no physical device\n"); return 1; }
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(phys, &props);
    printf("device: %s (driver api %u.%u)\n", props.deviceName, VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion));

    uint32_t n = 0;
    vkEnumerateDeviceExtensionProperties(phys, NULL, &n, NULL);
    VkExtensionProperties *exts = calloc(n, sizeof *exts);
    vkEnumerateDeviceExtensionProperties(phys, NULL, &n, exts);
    const char *wanted[] = { "VK_KHR_external_memory", "VK_KHR_external_memory_fd", "VK_EXT_external_memory_dma_buf",
                             "VK_EXT_image_drm_format_modifier", "VK_KHR_external_semaphore_fd", "VK_KHR_external_fence_fd",
                             "VK_ANDROID_external_memory_android_hardware_buffer" };
    for (unsigned i = 0; i < sizeof wanted / sizeof *wanted; i++) printf("  %-52s %s\n", wanted[i], has(exts, n, wanted[i]) ? "yes" : "no");
    if (!has(exts, n, "VK_KHR_external_memory_fd")) { printf("no fd export at all\n"); return 2; }

    /* Does the driver say a linear RGBA8 colour image can be exported as a dma-buf? */
    VkPhysicalDeviceExternalImageFormatInfo ext = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO,
        .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT };
    VkPhysicalDeviceImageFormatInfo2 info = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2, .pNext = &ext,
        .format = VK_FORMAT_B8G8R8A8_UNORM, .type = VK_IMAGE_TYPE_2D, .tiling = VK_IMAGE_TILING_LINEAR,
        .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT };
    VkExternalImageFormatProperties extProps = { .sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES };
    VkImageFormatProperties2 out = { .sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2, .pNext = &extProps };
    VkResult r = vkGetPhysicalDeviceImageFormatProperties2(phys, &info, &out);
    printf("linear B8G8R8A8 as dma-buf: result %d, features 0x%x (1=exportable 2=importable)\n", r,
           extProps.externalMemoryProperties.externalMemoryFeatures);

    float prio = 1;
    VkDeviceQueueCreateInfo q = { .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueFamilyIndex = 0, .queueCount = 1, .pQueuePriorities = &prio };
    const char *devExts[3]; int ne = 0;
    devExts[ne++] = "VK_KHR_external_memory"; devExts[ne++] = "VK_KHR_external_memory_fd";
    if (has(exts, n, "VK_EXT_external_memory_dma_buf")) devExts[ne++] = "VK_EXT_external_memory_dma_buf";
    VkDeviceCreateInfo dci = { .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1, .pQueueCreateInfos = &q,
        .enabledExtensionCount = ne, .ppEnabledExtensionNames = devExts };
    VkDevice dev;
    STEP(vkCreateDevice(phys, &dci, NULL, &dev));
#define DFN(name) PFN_##name name = (PFN_##name)vkGetDeviceProcAddr(dev, #name)
    DFN(vkCreateImage); DFN(vkGetImageMemoryRequirements); DFN(vkAllocateMemory); DFN(vkBindImageMemory);
    DFN(vkGetMemoryFdKHR); DFN(vkGetImageSubresourceLayout);

    /* What row pitch does the driver give a linear image of each width? A consumer has to agree with it. */
    static const int widths[] = { 500, 640, 800, 1024, 1280, 1366, 1600, 1920, 2560 };
    for (unsigned i = 0; i < sizeof widths / sizeof *widths; i++) {
        VkExternalMemoryImageCreateInfo e2 = { .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
            .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT };
        VkImageCreateInfo i2 = { .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .pNext = &e2, .imageType = VK_IMAGE_TYPE_2D,
            .format = VK_FORMAT_B8G8R8A8_UNORM, .extent = { widths[i], 600, 1 }, .mipLevels = 1, .arrayLayers = 1,
            .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_LINEAR,
            .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED };
        VkImage im;
        if (vkCreateImage(dev, &i2, NULL, &im) != VK_SUCCESS) { printf("width %d: create failed\n", widths[i]); continue; }
        VkImageSubresource sb = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0 };
        VkSubresourceLayout ly;
        vkGetImageSubresourceLayout(dev, im, &sb, &ly);
        VkMemoryRequirements rq;
        vkGetImageMemoryRequirements(dev, im, &rq);
        printf("width %4d: row pitch %5llu (width*4 = %d), memory %llu\n", widths[i], (unsigned long long)ly.rowPitch, widths[i] * 4, (unsigned long long)rq.size);
    }

    for (int handleKind = 0; handleKind < 2; handleKind++) {
        VkExternalMemoryHandleTypeFlagBits type = handleKind ? VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT
                                                              : VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
        printf("-- export as %s\n", handleKind ? "OPAQUE_FD" : "DMA_BUF");
        VkExternalMemoryImageCreateInfo eici = { .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, .handleTypes = type };
        VkImageCreateInfo imgi = { .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .pNext = &eici, .imageType = VK_IMAGE_TYPE_2D,
            .format = VK_FORMAT_B8G8R8A8_UNORM, .extent = { W, H, 1 }, .mipLevels = 1, .arrayLayers = 1,
            .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_LINEAR,
            .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED };
        VkImage image;
        VkResult cr = vkCreateImage(dev, &imgi, NULL, &image);
        if (cr != VK_SUCCESS) { printf("   vkCreateImage -> %d\n", cr); continue; }
        VkMemoryRequirements req;
        vkGetImageMemoryRequirements(dev, image, &req);
        VkImageSubresource sub = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0 };
        VkSubresourceLayout layout;
        vkGetImageSubresourceLayout(dev, image, &sub, &layout);
        printf("   size %llu, memoryTypeBits 0x%x, row pitch %llu\n", (unsigned long long)req.size, req.memoryTypeBits, (unsigned long long)layout.rowPitch);
        int mt = 0;
        while (mt < 32 && !(req.memoryTypeBits & (1u << mt))) mt++;
        VkExportMemoryAllocateInfo exp = { .sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO, .handleTypes = type };
        VkMemoryAllocateInfo mai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &exp, .allocationSize = req.size, .memoryTypeIndex = mt };
        VkDeviceMemory mem;
        VkResult ar = vkAllocateMemory(dev, &mai, NULL, &mem);
        if (ar != VK_SUCCESS) { printf("   vkAllocateMemory -> %d\n", ar); continue; }
        vkBindImageMemory(dev, image, mem, 0);
        VkMemoryGetFdInfoKHR gfi = { .sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR, .memory = mem, .handleType = type };
        int fd = -1;
        VkResult fr = vkGetMemoryFdKHR(dev, &gfi, &fd);
        printf("   vkGetMemoryFdKHR -> %d, fd %d\n", fr, fd);
        if (fr != VK_SUCCESS || fd < 0) continue;
        struct stat st;
        fstat(fd, &st);
        char link[64], target[256] = "";
        snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
        ssize_t len = readlink(link, target, sizeof target - 1);
        if (len > 0) target[len] = 0;
        printf("   fd is %s, size %lld, links to '%s'\n", S_ISCHR(st.st_mode) ? "a char device" : S_ISREG(st.st_mode) ? "a file" : "something else",
               (long long)st.st_size, target);
        void *map = mmap(NULL, req.size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        printf("   mmap: %s\n", map == MAP_FAILED ? "failed" : "ok");
        if (map != MAP_FAILED) munmap(map, req.size);
        close(fd);
    }
    return 0;
}
