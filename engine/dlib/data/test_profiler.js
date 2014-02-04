var fs = require('fs');
var profiler = require('profilerX.js');
var assert = require('assert');

function testLoad() {
    var stringsChunk = fs.readFileSync('strings.dat', 'binary');
    var stringTable = profiler.loadStrings(stringsChunk);

    var profileChunk = fs.readFileSync('profile.dat', 'binary');
    var profile = profiler.loadProfile(profileChunk, stringTable);

    //console.log(stringTable);
    //console.log(profile);
}

var testData = {
    samples : [ {
        // 0.001 + 15.936 = 15.937
        start : 0.001,
        elapsed : 15.936,
        name : 'Engine.Frame',
        scopeName : 'Engine',
    }, {
        start : 0.023,
        elapsed : 0.398,
        name : 'Engine.Sim',
        scopeName : 'Engine',
    }, {
        // NOTE: Due to floating-point issue
        // end-value is actually larger here
        // but should be within Engine.Frame
        // This is accounted for (epsilon)

        // 0.422 + 15.515 = 15.937000000000001
        start : 0.422,
        elapsed : 15.515,
        name : 'Graphics.Flip',
        scopeName : 'Graphics',
    }, ],
};

var testData2 = {
    samples : [ {
        start: 504.441,
        elapsed: 0.56,
        name: "Resource.Load",
        scopeName: "Resource",
    }, {
        start: 504.479,
        elapsed: 0.024,
        name: "DDF.LoadMessage",
        scopeName: "DDF",
    }, {
        start: 504.515,
        elapsed: 0.472,
        name: "Resource.Get",
        scopeName: "Resource",
    }, {
        start: 504.523,
        elapsed: 0.464,
        name: "Resource.Load",
        scopeName: "Resource",
    }, ],
};


function testCallTree() {

    var eps = 0.00001;
    var root = profiler.callTree(testData);
    var x = root.children;
//    console.log(JSON.stringify(x, null, 2));

    assert(x.length == 1);
    assert(x[0].children.length == 2);
    assert(x[0].children[0].children.length == 0);
    assert(x[0].children[1].children.length == 0);

    assert(Math.abs(x[0].self - 0.0229999) < eps);
    assert(Math.abs(x[0].children[0].self - 0.398) < eps);
    assert(Math.abs(x[0].children[1].self - 15.515) < eps);

    var sum = x[0].self + x[0].children[0].self + x[0].children[1].self;
    assert(x[0].elapsed == sum);
}

function testCallTree2() {
    var eps = 0.00001;
    var root = profiler.callTree(testData2);
    var x = root.children;

    assert(x.length == 1);

    assert(x[0].children.length == 2);
    assert(x[0].children[0].children.length == 0);
    assert(x[0].children[1].children.length == 1);
    assert(x[0].children[1].children[0].children.length == 0);

    //console.log(JSON.stringify(root, null, 2));
}

function testFlatten() {
    var eps = 0.00001;
    var root = profiler.callTree(testData);
    var lst = profiler.flatten(root);
    assert(lst[0].name == 'Engine.Frame');
    assert(lst[1].name == 'Engine.Sim');
    assert(lst[2].name == 'Graphics.Flip');
}

testLoad();
testCallTree();
testCallTree2();
testFlatten();
console.log("all passed");
