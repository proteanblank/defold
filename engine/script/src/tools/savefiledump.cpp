#include <stdio.h>
#include <unistd.h>
#include <dlib/dlib.h>
#include <dlib/dstrings.h>
#include <dlib/log.h>
#include <dlib/align.h>
#include <dlib/math.h>
#include "../script.h"

#if defined(WIN32)
#include <io.h>
#define access _access
#endif

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
    return access( path, 0 ) != -1;
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

    const char* path = argv[1];

    if (!FileExists(path))
    {
        printf("File does not exist: '%s'\n", path);
        return 1;
    }

    Init();


    int ret = LoadSaveFile(path);
    if (ret)
    {
        printf("Loading %s failed!\n", path);
    }

	Exit();
	return ret;
}