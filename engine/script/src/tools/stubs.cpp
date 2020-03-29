
#include <extension/extension.h>

namespace dmExtension
{
    const Desc* GetFirstExtension() { return 0; }
   	Params::Params() {}
}

#include <ddf/ddf.h>

namespace dmDDF
{
    const Descriptor* GetDescriptorFromHash(dmhash_t hash) { return 0; }

    InternalRegisterDescriptor::InternalRegisterDescriptor(Descriptor* descriptor)
    {
    }

}

#include <resource/resource.h>

namespace dmResource
{
    Result GetRaw(HFactory factory, const char* name, void** resource, uint32_t* resource_size) { return RESULT_UNKNOWN_ERROR; }
    void Release(HFactory factory, void* resource) {}
}

