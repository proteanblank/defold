#include <stdio.h>
#include <unistd.h>
#include <dlib/dlib.h>
#include <dlib/dstrings.h>
#include <dlib/log.h>
#include <dlib/align.h>
#include <dlib/math.h>
#include "../script.h"

static void Usage()
{
    printf("Usage: savefiledump <path>\n");
}

static bool RunString(lua_State* L, const char* script)
{
    if (luaL_dostring(L, script) != 0)
    {
        dmLogError("%s", lua_tolstring(L, -1, 0));
        lua_pop(L, 1); // lua error
        return false;
    }
    printf("OK: '%s'\n", script);
    return true;
}




dmScript::HContext g_Context = 0;
int g_Top = 0;
lua_State* g_L = 0;

static bool FileExists(const char* path)
{
    return access( path, F_OK ) != -1;
}

static void Init()
{
    g_Context = dmScript::NewContext(0, 0, true);
    dmScript::Initialize(g_Context);
    g_L = dmScript::GetLuaState(g_Context);
    g_Top = lua_gettop(g_L);
}

static void Exit()
{
    if (g_Top != lua_gettop(g_L))
    {
    	printf("Stack is incorrect. Wanted %d, got %d\n", g_Top, lua_gettop(g_L));
    }
    dmScript::Finalize(g_Context);
    dmScript::DeleteContext(g_Context);
}

const uint32_t MAX_BUFFER_SIZE = 512 * 1024;

union SaveLoadBuffer
{
    uint32_t m_alignment; // This alignment is required for js-web
    char m_buffer[MAX_BUFFER_SIZE]; // Resides in .bss
} g_saveload;

static int SaveFile(const char* path)
{
    lua_State* L = g_L;
    lua_newtable(L);


    //lua_pushstring(L, dmScript::PushNu);
    lua_pushnumber(L, 1.00);
    lua_setfield(L, -2, "var8");

    lua_pushnumber(L, 1.000000);
    lua_setfield(L, -2, "var8");
    
    lua_pushnumber(L, 0.541146);
    lua_setfield(L, -2, "var6");
    
    lua_pushnumber(L, 0.500000);
    lua_setfield(L, -2, "var13");
    
    lua_pushnumber(L, 1);
    lua_setfield(L, -2, "var12");
    
    lua_pushnumber(L, 1);
    lua_setfield(L, -2, "var16");
    
    lua_pushnumber(L, 1);
    lua_setfield(L, -2, "var5");
    
    lua_pushnumber(L, 0);
    lua_setfield(L, -2, "var7");
    
    lua_pushnumber(L, 1.000000);
    lua_setfield(L, -2, "var1");
    
    lua_pushnumber(L, 1.000000);
    lua_setfield(L, -2, "var3");
    
    lua_pushnumber(L, 1);
    lua_setfield(L, -2, "var15");
    
    lua_pushnumber(L, 1);
    lua_setfield(L, -2, "var4");
    
    //lua_pushnumber(0.000000, 0.000000, 1920.000000, 1080.000000);
    //dmScript::PushVector4(L, )
    //lua_setfield(L, -2, "var9");


    lua_newtable(L);
    dmScript::PushHash(L, 0x12345678);
    lua_setfield(L, -2, "trigger");
    lua_setfield(L, -2, "PAD_INVENTORY_DROP");

    lua_newtable(L);
    dmScript::PushHash(L, 0x12345678);
    lua_setfield(L, -2, "trigger");
    lua_setfield(L, -2, "PAD_MOVE_TIME");


    luaL_checktype(g_L, 1, LUA_TTABLE);
    uint32_t n_used = dmScript::CheckTable(g_L, g_saveload.m_buffer, sizeof(g_saveload.m_buffer), 1);

    lua_pop(L,1);

    FILE* f = fopen(path, "wb");
    if (!f)
    {
        printf("Failed to open %s for writing\n", path);
        return 1;
    }

    bool result = fwrite(g_saveload.m_buffer, 1, n_used, f) == n_used;
    fclose(f);

    if (result)
        printf("Wrote %s successfully\n", path);
    else
        printf("Wrote to write %s\n", path);

    return result ? 0 : 1;
}

static int LoadSaveFile(const char* path)
{
    printf("Loading %s...\n", path);
    char buffer[1024];
    dmSnPrintf(buffer, sizeof(buffer), "sys.load('%s')", path);
    bool result = RunString(g_L, buffer);
	return result ? 0 : 1;
}

int main(int argc, char** argv)
{
    dLib::SetDebugMode(true);

    if (argc < 2)
    {
        Usage();
        return 0;
    }

    int savemode = argc >= 3;

    const char* path = argv[1];

    if (!savemode)
    {
        if (!FileExists(path))
        {
            printf("File does not exist: '%s'\n", path);
            return 1;
        }
    }

    Init();

    int ret;
    if (savemode)
    {
        ret = SaveFile(path);
        if (ret)
        {
            printf("Saving %s failed!\n", path);
        }
    }
    else
    {
        ret = LoadSaveFile(path);
        if (ret)
        {
            printf("Loading %s failed!\n", path);
        }
    }


	Exit();
	return ret;
}