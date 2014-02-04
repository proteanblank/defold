var runCapture = false;

function capture(base_url, doneFunc) {
    var request = new XMLHttpRequest();
    var capturedFrameCount = 0;
    var captured = [];
    var profile = [];

    runCapture = true;

    $('#captureCount').text('');
    $("#capturing").show();

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
                        $('#captureCount').text(capturedFrameCount);
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

                    $("#capturing").hide();
                    doneFunc(profile);
                } else {
                    $("#capturing").hide();
                    alert("Unknown chunk type: " + type);
                }
            } else {
                //$("#capturing").hide();
                //alert("Failed to load data");
            }
        }
    }
    getChunk('profile');
}

function displayStack(profile) {
    $('#frameSumChart').empty();
    var minChartWidth = 900; // TODO:
    var skipSync = false;
    var skipProfiler = false;

    var threshold = 0.1;
    var callTree = profile.map(profiler.callTree);
    foo = profile.map(profiler.callTree);;
    var topFrames = callTree.map(profiler.flatten);
    bar = topFrames;
    topFrames = topFrames.map(function(x) {
        return x.filter(function(s) {
            if (skipSync && s.name == "VSync.Wait") {
                return false;
            }
            else if (skipProfiler && s.name == "Profiler.Overhead") {
                return false;
            } else {
                return s.self >= threshold;
            }
        })
    });

    var totals = {}
    for (var i in topFrames) {
        var f = topFrames[i];
        for (var j in f) {
            var s = f[j];
            if (!(s.name in totals)) {
                totals[s.name] = 0;
            }
            totals[s.name] += s.self;
        }
    }

    topNames = []
    Object.keys(totals).map(function(x) {
        topNames.push([x, totals[x]]);
    });
    topNames.sort(function(x, y) { return y[1] - x[1]});
    topNames = topNames.map(function(x) { return x[0]; });
    topNames = topNames.slice(0,6);
    topNames.push("Other");

    var domainY = 0;
    /*var nameSet = {}
    for (var i in topFrames) {
        var f = topFrames[i];
        for ( var j in f) {
            nameSet[f[j].name] = true;
            //domainY = Math.max(domainY, f[j].self);
        }
    }*/
    domainY = Math.max(domainY, 40);
    //var names = Object.keys(nameSet);

    var data = []
    for (var i in topFrames) {
        var f = topFrames[i];
        var x = { frame: parseInt(i) };

        for (var j = 0; j < topNames.length; ++j) {
            x[topNames[j]] = 0;
        }

        var other = 0;
        for ( var j in f) {
            if (topNames.indexOf(f[j].name) != -1) {
                x[f[j].name] = f[j].self;
            } else {
                other += f[j].self;
            }
        }
        x["Other"] = other;
        data.push(x);
    }

    var frameCount = profile.length;
    var margin = {
        top : 20,
        right : 20,
        bottom : 30,
        left : 50
    },
    // TODO: 10 should be a constant
    width = Math.max(minChartWidth, frameCount * 10) - margin.left
            - margin.right, height = 500 - margin.top - margin.bottom;

    var x = d3.scale.linear().range([ 0, width ]);
    var y = d3.scale.linear().domain([ 0, domainY * 1.1 ]).range([ height, 0 ]);
    var color = d3.scale.category20();
    var xAxis = d3.svg.axis().scale(x).orient("bottom");
    var yAxis = d3.svg.axis().scale(y).orient("left");

    var area = d3.svg.area()
        .x(function(d) { return x(d.frame); })
        .y0(function(d) { return y(d.y0); })
        .y1(function(d) { return y(d.y0 + d.y); });

    var stack = d3.layout.stack().values(function(d) {
        return d.values;
    });

    var svg = d3.select("#frameSumChart")
        .append("svg")
        .attr("width", width + margin.left + margin.right)
        .attr("height", height + margin.top + margin.bottom)
        .append("g")
        .attr("transform", "translate(" + margin.left + "," + margin.top + ")");

//    color.domain(d3.keys(nameSet));
    color.domain(topNames);

    var browsers = stack(color.domain().map(function(name) {
        return {
            name : name,
            values : data.map(function(d) {
                return {
                    frame : d.frame,
                    y : d[name]
                };
            })
        };
    }));

    x.domain(d3.extent(data, function(d) {
        return d.frame;
    }));

    var browser = svg.selectAll(".browser")
        .data(browsers).enter().append("g")
        .attr("class", "browser");

    browser.append("path")
        .attr("class", "area")
        .attr("d", function(d) { return area(d.values); })
        .attr("data-legend", function(d) { return d.name })
        .attr("data-legend-color", function(d) { return color(d.name) })
        .style("fill", function(d) { return color(d.name); });

    /////////
    /*
    var state = svg.selectAll(".state")
            .data(data)
        .enter().append("g")
            .attr("class", "g")
            .attr("transform", function(d) { return "translate(" + x(d.State) + ",0)"; });

    var xx = d3.scale.ordinal()
    .rangeRoundBands([0, width], .1);

    state.selectAll("rect")
    .data(function(d) { return data; })
  .enter().append("rect")
    .attr("width", xx.rangeBand())
    .attr("y", function(d) { return y(d.y1); })
    .attr("height", function(d) { return y(d.y0) - y(d.y1); })
    .style("fill", function(d) { return color(d.name); });
*/
    /////////

    svg.append("g")
        .attr("class", "x axis")
        .attr("transform", "translate(0," + height + ")")
        .call(xAxis);

    svg.append("g")
        .attr("class", "y axis")
        .call(yAxis);

    svg.append("line")
        .attr("x1", 0)
        .attr("x2", width)
        .attr("y1", y(1000 / 60.0))
        .attr("y2", y(1000 / 60.0)).style("stroke-dasharray", ("3, 3"))
        .style("stroke", "#aaa");

    var legend = svg.append("g")
        .attr("class", "legend")
        .attr("transform", "translate(50,30)")
        .style("font-size", "12px")
        .call(d3.legend)

}

function startCapture() {
    //var url = 'http://172.16.10.119:8002/';
    var url = 'http://localhost:8002/';
    //var url = 'http://10.0.1.6:8002/';
    //var url = 'http://192.168.1.2:8002/';
    //var url = 'http://192.168.2.6:8002/';
    capture(url, function(profile) {
        displayStack(profile);
    });
}

function onCapture() {
    if (runCapture) {
        $('#captureButton').text("Capture");
        runCapture = false;
    } else {
        $('#captureButton').text("Stop");
        startCapture();
    }
}
