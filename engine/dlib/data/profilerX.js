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
            var start = readUInt32(d, offset + 8);
            var elapsed = readUInt32(d, offset + 12);
            var threadId = readUInt16(d, offset + 16);
            var name = table[nameID];

            var scopeName = table[scope];
            offset += 5 * 4;

            frameTime = Math.max(frameTime, elapsed / ticksPerSecond);

            var s = {
                scopeName : scopeName,
                name : scopeName + "." + name,
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
            children : []
        };
    }

    function callTreeFrame(samples, start, max) {
        var s = samples[start];
        var current = newNode(s);
        var nodes = [ current ];

        var i;
        for (i = start + 1; i < samples.length; i++) {
            var s = samples[i];
            var end = s.start + s.elapsed;

            // TODO: Correct range? Add depth to samples instead?
            if (end > max) {
                // ++i;
                --i;
                break;
            }
            // Avoid floating point comparison error
            // If a child is taken for a sibling scope the total time will be
            // totally wrong
            var epsilon = 0.00001;
            // TODO: Correct range? Add depth to samples instead?
            if ((end - epsilon) <= current.end) {
                var ret = callTreeFrame(samples, i, end);
                i = ret.start;
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
        node.children = [];
        return ret;
    }

    module.loadStrings = loadStrings;
    module.loadProfile = loadProfile;
    module.callTree = callTree;
    module.flatten = flatten;

    return module;
}());

// For node.js
if (typeof exports != 'undefined') {
    exports.loadStrings = profiler.loadStrings;
    exports.loadProfile = profiler.loadProfile;
    exports.callTree = profiler.callTree;
    exports.flatten = profiler.flatten;
}
