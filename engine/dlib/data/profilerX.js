/*
 * Data structures
 * All time-values in milliseconds
 *
 * Samples:
 *  - scopeName string
 *  - name      string  (scope.sample)
 *  - start     number
 *  - elapsed   number
 *
 *  Scope:
 *   - elapsed number
 *   - count   number
 *
 *  Counter:
 *   - value [number]
 *
 *  Profile:
 *   - samples [Sample]
 *   - frameTime number
 *   - scopes  [Scope]
 *   - counters [Counter]
 *
 *  Limitations. Only compatible with 32-bit architecture.
 */

var profiler = (function() {
    var module = {};
    var ticksPerSecond = 1000.0; // NOTE: We use ms internally
    var runCapture = false;

    readUInt32 = function(data, offset) {
        var a1 = data.charCodeAt(offset + 3) & 0xff;
        var a2 = data.charCodeAt(offset + 2) & 0xff;
        var a3 = data.charCodeAt(offset + 1) & 0xff;
        var a4 = data.charCodeAt(offset + 0) & 0xff;
        return (a1 << 24) + (a2 << 16) + (a3 << 8) + a4;
    }

    readUInt16 = function(data, offset) {
        var a1 = data.charCodeAt(offset + 1) & 0xff;
        var a2 = data.charCodeAt(offset + 0) & 0xff;
        return (a1 << 8) + a2;
    }

    function loadStrings(d) {
        var table = {}
        var stringCount = readUInt32(d, 4);
        var offset = 8;
        for ( var i = 0; i < stringCount; ++i) {
            var id = readUInt32(d, offset + 0);
            var len = readUInt16(d, offset + 4);
            var str = d.substring(offset + 6, offset + 6 + len);

            table[id] = str;

            offset += len;
            offset += 4 + 2;
        }
        return table;
    }

    function loadProfile(d, table) {
        var samples = [];
        var sampleCount = readUInt32(d, 4);
        var offset = 8;
        var frameTime = 0;
        for ( var i = 0; i < sampleCount; ++i) {
            var nameID = readUInt32(d, offset + 0);
            var scope = readUInt32(d, offset + 4);
            var arg = readUInt32(d, offset + 8);
            var start = readUInt32(d, offset + 12);
            var elapsed = readUInt32(d, offset + 16);
            var threadId = readUInt16(d, offset + 20);
            var name = table[nameID];

            var scopeName = table[scope];
            if (name == undefined) {
                console.log( nameID, name, scope, scopeName );

            }
            offset += 6 * 4;

            frameTime = Math.max(frameTime, elapsed / ticksPerSecond);

            var s = {
                scopeName : scopeName,
                name : scopeName + "." + name,
                arg: arg,
                start : start / ticksPerSecond,
                elapsed : elapsed / ticksPerSecond
            };
            samples.push(s);
        }

        var scopes = [];
        var scopeCount = readUInt32(d, offset);
        offset += 4;
        for ( var i = 0; i < scopeCount; ++i) {
            var nameID = readUInt32(d, offset + 0);
            var elapsed = readUInt32(d, offset + 4) / ticksPerSecond;
            var count = readUInt32(d, offset + 8);
            offset += 3 * 4;
            var name = table[nameID];

            scopes[name] = {
                elapsed : elapsed,
                count : count
            };
        }

        var counters = [];
        var counterCount = readUInt32(d, offset);
        offset += 4;
        for ( var i = 0; i < counterCount; ++i) {
            var nameID = readUInt32(d, offset + 0);
            var value = readUInt32(d, offset + 4);
            offset += 2 * 4;
            var name = table[nameID];

            counters[name] = {
                value : value
            };
        }

        return {
            samples : samples,
            frameTime : frameTime,
            scopes : scopes,
            counters : counters
        };
    }

    function newNode(s) {
        return {
            start : s.start,
            end : s.start + s.elapsed,
            elapsed : s.elapsed,
            name : s.name,
            scopeName : s.scopeName,
            arg: s.arg,
            parent: null,
            children : []
        };
    }

    function callTreeFrame(samples, start, max) {
        // TODO: We must check thread
        var s = samples[start];
        var current = newNode(s);
        var nodes = [ current ];

        var i;
        for (i = start + 1; i < samples.length; i++) {
            var s = samples[i];
            var end = s.start + s.elapsed;

            // Avoid floating point comparison error
            // If a child is taken for a sibling scope the total time will be
            // totally wrong
            var epsilon = 0.00001;

            // TODO: Correct range? Add depth to samples instead?
            if ((end - epsilon) > max) {
                // ++i;
                --i;
                break;
            }
            // TODO: Correct range? Add depth to samples instead?

//            if ((s.start + epsilon)  >= current.start && (end - epsilon)  <= current.end) {
            if ((end - epsilon) <= current.end) {
                var ret = callTreeFrame(samples, i, end);
                i = ret.start;
                // TODO: Write test for this
                for (var j in ret.nodes) {
                    ret.nodes[j].parent = current;
                }
                current.children = current.children.concat(ret.nodes);
            } else {
                current = newNode(s);
                nodes.push(current);
            }
        }

        return {
            nodes : nodes,
            start : i
        };
    }

    function calcSelfTime(node) {
        var sum = 0;
        for ( var i in node.children) {
            var c = node.children[i];
            sum += c.end - c.start;
            calcSelfTime(c);
        }
        node.self = (node.end - node.start) - sum;
        if (node.self < 0) {
            debugger;
        }
    }

    function callTree(profile) {
        var frames = [];
        var ret = callTreeFrame(profile.samples, 0, Number.MAX_VALUE);

        var start = Number.MAX_VALUE;
        var end = 0;
        for ( var i in ret.nodes) {
            var n = ret.nodes[i];
            calcSelfTime(n);

            start = Math.min(start, n.start);
            end = Math.max(end, n.end);
        }

        var root = {
            start : start,
            end : end,
            elapsed : end - start,
            name : "Root.Root",
            scopeName : "Root",
            children : ret.nodes
        };

        return root;
    }

    function flatten(node) {
        var ret = [];
        for (var i in node.children) {
            var c = node.children[i];
            ret.push(c);
            ret = ret.concat(flatten(c));
        }
        // TODO: This operation is destructive. We children = []
        //node.children = [];
        return ret;
    }

    function startCapture(base_url, progressFunc, doneFunc) {
        var request = new XMLHttpRequest();
        var capturedFrameCount = 0;
        var captured = [];
        var profile = [];

        runCapture = true;

        //$('#captureCount').text('');
        //$("#capturing").show();

        var getChunk = function(url) {
            request.open('GET', base_url + url, true);
            request.overrideMimeType('text/plain; charset=x-user-defined');
            request.onreadystatechange = handler;
            request.onerror = function() {
                if (runCapture) {
                    setTimeout(function(){
                        getChunk('profile');
                    }, 100);
                }
            }
            request.send();
        }

        var handler = function(evtXHR) {
            if (request.readyState == 4) {
                if (request.status == 200) {
                    var d = request.responseText;
                    var type = d.substring(0, 4);

                    if (type == "PROF") {
                        capturedFrameCount += 1;
                        captured.push(d);

                        if (capturedFrameCount % 10 == 0) {
                            progressFunc(capturedFrameCount);
                            // TODO: Callback for this
                            // $('#captureCount').text(capturedFrameCount);
                        }
                        if (capturedFrameCount < 2500 && runCapture) {
                            getChunk('profile')
                        } else {
                            getChunk('strings');
                        }
                    } else if (type == "STRS") {
                        var stringTable = profiler.loadStrings(d);

                        for (var i = 0; i < captured.length; ++i) {
                            var prof = profiler.loadProfile(captured[i], stringTable);
                            profile.push(prof);
                        }

                        //$("#capturing").hide();
                        doneFunc(profile, stringTable);
                    } else {
                        //$("#capturing").hide();
                        runCapture = false;
                        doneFunc(null);
                    }
                }
            }
        }
        getChunk('profile');
    }

    function stopCapture() {
        runCapture = false;
    }

    module.loadStrings = loadStrings;
    module.loadProfile = loadProfile;
    module.callTree = callTree;
    module.flatten = flatten;
    module.startCapture = startCapture;
    module.stopCapture = stopCapture;

    return module;
}());

// For node.js
if (typeof exports != 'undefined') {
    exports.loadStrings = profiler.loadStrings;
    exports.loadProfile = profiler.loadProfile;
    exports.callTree = profiler.callTree;
    exports.flatten = profiler.flatten;
}
