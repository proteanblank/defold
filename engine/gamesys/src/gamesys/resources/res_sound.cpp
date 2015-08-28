#include "res_sound.h"

#include <dlib/log.h>
#include <sound/sound.h>
#include "sound_ddf.h"

namespace dmGameSystem
{
    dmResource::Result AcquireResources(dmResource::HFactory factory,
                                        dmSoundDDF::SoundDesc* sound_desc,
                                        Sound** sound)
    {
        dmSound::HSoundData sound_data = 0;
        dmResource::Result fr;
        
        if (sound_desc->m_Sound[0])
        {
            fr = dmResource::Get(factory, sound_desc->m_Sound, (void**) &sound_data);
        }
        else
        {
            if (dmSound::NewSoundData(0, 0, dmSound::SOUND_DATA_TYPE_RAW, &sound_data) == dmSound::RESULT_OK)
                fr = dmResource::RESULT_OK;
            else
                fr = dmResource::RESULT_FORMAT_ERROR;
        }

        if (fr == dmResource::RESULT_OK)
        {
            Sound*s = new Sound();
            s->m_SoundData = sound_data;
            s->m_Looping = sound_desc->m_Looping;
            s->m_GroupHash = dmHashString64(sound_desc->m_Group);
            s->m_Gain = sound_desc->m_Gain;
            s->m_LoadedFromResource = (sound_desc->m_Sound[0] != 0);

            dmSound::Result result = dmSound::AddGroup(sound_desc->m_Group);
            if (result != dmSound::RESULT_OK) {
                dmLogError("Failed to create group '%s' (%d)", sound_desc->m_Group, result);
            }

            *sound = s;
        }

        dmDDF::FreeMessage(sound_desc);
        return fr;
    }


    dmResource::Result ResSoundPreload(dmResource::HFactory factory,
                                       dmResource::HPreloadHintInfo hint_info,
                                       void* context,
                                       const void* buffer, uint32_t buffer_size,
                                       void** preload_data,
                                       const char* filename)
    {
        dmSoundDDF::SoundDesc* sound_desc;
        dmDDF::Result e = dmDDF::LoadMessage<dmSoundDDF::SoundDesc>(buffer, buffer_size, &sound_desc);
        if ( e != dmDDF::RESULT_OK )
        {
            return dmResource::RESULT_FORMAT_ERROR;
        }
        
        dmResource::PreloadHint(hint_info, sound_desc->m_Sound);
        *preload_data = sound_desc;
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResSoundCreate(dmResource::HFactory factory,
                                           void* context,
                                           const void* buffer, uint32_t buffer_size,
                                           void* preload_data,
                                           dmResource::SResourceDescriptor* resource,
                                           const char* filename)
    {
        dmSoundDDF::SoundDesc* sound_desc = (dmSoundDDF::SoundDesc*) preload_data;
        Sound* sound;
        dmResource::Result r = AcquireResources(factory, sound_desc, &sound);
        if (r == dmResource::RESULT_OK)
        {
            resource->m_Resource = (void*) sound;
        }
        return r;
    }

    dmResource::Result ResSoundDestroy(dmResource::HFactory factory,
                                            void* context,
                                            dmResource::SResourceDescriptor* resource)
    {
        Sound* sound = (Sound*) resource->m_Resource;

        if (sound->m_LoadedFromResource)
        {
            dmResource::Release(factory, (void*) sound->m_SoundData);
        }
        else
        {
            dmSound::DeleteSoundData(sound->m_SoundData);
        }
        delete sound;
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResSoundRecreate(dmResource::HFactory factory,
            void* context,
            const void* buffer, uint32_t buffer_size,
            dmResource::SResourceDescriptor* resource,
            const char* filename)
    {
        // Not supported yet. This might be difficult to set a new HSoundInstance
        // while a sound is playing?
        return dmResource::RESULT_OK;
    }
}
