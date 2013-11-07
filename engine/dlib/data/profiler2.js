var ticksPerSecond = 1000.0; // NOTE: We use ms internally
var minChartWidth = 900;

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
	var string_count = readUInt32(d, 4);
	var offset = 8;
	for ( var i = 0; i < string_count; ++i) {
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
	//var frame_number = readUInt32(d, 0);
	var samples = [];
	var sample_count = readUInt32(d, 0);
	var offset = 4;
	var frameTime = 0;
	for ( var i = 0; i < sample_count; ++i) {
		var name_id = readUInt32(d, offset + 0);
		var scope = readUInt32(d, offset + 4);
		var start = readUInt32(d, offset + 8);
		var elapsed = readUInt32(d, offset + 12);
		var thread_id = readUInt16(d, offset + 16);
		var name = table[name_id];

		var scope_name = table[scope];
		offset += 5 * 4;

		frameTime = Math.max(frameTime, elapsed / ticksPerSecond);

		var s = {
			scope_name : scope_name,
			name : scope_name + "." + name,
			start : start / ticksPerSecond,
			elapsed : elapsed / ticksPerSecond
		};
		samples.push(s);
	}

	var scopes_data = [];
	var scope_count = readUInt32(d, offset);
	offset += 4;
	for ( var i = 0; i < scope_count; ++i) {
		var name_id = readUInt32(d, offset + 0);
		var elapsed = readUInt32(d, offset + 4) / ticksPerSecond;
		var count = readUInt32(d, offset + 8);
		offset += 3 * 4;
		var name = table[name_id];

		scopes_data[name] = {
			elapsed : elapsed,
			count : count
		};
	}

	var counters_data = [];
	var counter_count = readUInt32(d, offset);
	offset += 4;
	for ( var i = 0; i < counter_count; ++i) {
		var name_id = readUInt32(d, offset + 0);
		var value = readUInt32(d, offset + 4);
		offset += 2 * 4;
		var name = table[name_id];

		counters_data[name] = {
			value : value
		};
	}

	return {
		samples : samples,
		frame_time : frameTime,
		scopes_data : scopes_data,
		counters_data : counters_data
	};
}

var runCapture = false;

function capture(base_url, doneFunc) {
	var request = new XMLHttpRequest();
	var capturedFrameCount = 0;
	var profile = [];

	runCapture = true;

    $('#captureCount').text('');
    $("#capturing").show();

	var getChunk = function(url){
	    request.open('GET', base_url + url, true);
	    request.overrideMimeType('text/plain; charset=x-user-defined');
	    request.onreadystatechange = handler;
	    request.send();
	}

	var handler = function(evtXHR) {
	    if (request.readyState == 4) {
	        if (request.status == 200) {
	            var d = request.responseText;

	            var type = d.substring(0, 4);
	            if (type == "PROF") {
	                capturedFrameCount += 1;
	                var prof = loadProfile(d.substring(4), stringTable);
	                profile.push(prof);

	                if (capturedFrameCount % 10 == 0) {
                        $('#captureCount').text(capturedFrameCount);
	                }
	                if (capturedFrameCount < 2500 && runCapture) {
	                    getChunk('profile')
	                }
	                else {
                        $("#capturing").hide();
	                	doneFunc(profile);
	                }
	            }
	            else if (type == "STRS") {
	                stringTable = loadStrings(d);
	                console.log(stringTable);
	                getChunk('profile');
	            }
	            else {
	                $("#capturing").hide();
	                alert("Unknown chunk type: " + type);
	            }
	        }
	        else {
	            $("#capturing").hide();
	            alert("Failed to load data");
	        }
	    }
	}
	getChunk('strings');
}

function test() {
	var w = 600;
	var h = 600;
	var padding = 20;

	var dataset = [10, 40, 30, 40, 30, 40, 30, 40, 30, 40, 30, 40];

	var xScale = d3.scale.linear()
	        .domain([0, d3.max(dataset, function(d){
	                                return d; })]) //note I'm using an array here to grab the value hence the [0]
	        .range([padding, w - (padding*2)]);

    var yScale = d3.scale.ordinal()
        .domain(d3.range(dataset.length))
        .rangeRoundBands([padding, dataset.length * 50 - padding], 0.1);

    var formatPercent = d3.format(".0%");

    var yAxis = d3.svg.axis()
    	.scale(yScale)
    	.orient("left")
	    .tickFormat(formatPercent);


    var svg = d3.select("body")
        .append("svg")
        .attr("width", w)
        .attr("height", h)

    svg.append("g")
      .attr("class", "y axis")
      .call(yAxis)
    .append("text")
      .attr("transform", "rotate(-90)")
      .attr("y", 6)
      .attr("dy", ".71em")
      .style("text-anchor", "end")
      .text("Frequency");

    svg.selectAll(".rule")
         .data(xScale.ticks(10))
       .enter().append("text")
         .attr("class", "rule")
         .attr("x", xScale)
         .attr("y", 0)
         .attr("dy", -3)
         .attr("text-anchor", "middle")
         .text(String);

    svg.selectAll("rect")
        .data(dataset)
        .enter()
        .append("rect")
        .attr("x", 0 + padding)
        .attr("y", function(d, i){
        return yScale(i);
        })
        .attr("width", function(d) {
            return xScale(d);
        })
        .attr("height", yScale.rangeBand());
}

function test2() {
	 var data = [4, 8, 15, 16, 120];
	 var yData = ["main", "gfx", "anim", "sound", "particle"];
	 var xmargin = 50;
	 var padding = 30;

	 var chart = d3.select("body").append("svg")
		     .attr("class", "chart")
		     .attr("width", 420 + xmargin)
		     .attr("height", 20 * data.length + padding);

	 var x = d3.scale.linear()
     	.domain([0, d3.max(data)])
     	.range([0, 420 + xmargin]);

	 chart.selectAll("rect")
	     .data(data)
	     .enter().append("rect")
	     .attr("x", xmargin)
	     .attr("y", function(d, i) { return i * 20 + padding; })
	     .attr("width", x)
	     .attr("height", 20);

	 var y = d3.scale.ordinal()
     	.domain(yData)
     	.rangeBands([0, 20 * data.length]);

	 var xAxis = d3.svg.axis()
	    .scale(x)
	    .orient("top");

	 chart.append("g")         // Add the X Axis
	  	.attr("class", "x axis")
	  	.attr("transform", "translate("+xmargin + "," + padding + ")")
	  	.call(xAxis);

	 var yAxis = d3.svg.axis()
	    .scale(y)
	    .orient("right");

	 chart.append("g")         // Add the X Axis
	  	.attr("class", "y axis")
	  	.attr("transform", "translate("+ "0" + "," + (padding + 0) + ")")
	  	.call(yAxis);


	 /*	 chart.selectAll("rect")
     .data(data)
     .enter().append("rect")
     .attr("y", y)
     .attr("width", x)
     .attr("height", y.rangeBand());
*/
/*
	 chart.selectAll("text")
	      .data(data)
	    .enter().append("text")
	      .attr("x", x)
	      .attr("y", function(d) { return y(d) + y.rangeBand() / 2; })
	      .attr("dx", -3) // padding-right
	      .attr("dy", ".35em") // vertical-align: middle
	      .attr("text-anchor", "end") // text-align: right
	      .text(String);
*/
	 /*var chart = d3.select("body").append("svg")
	      .attr("class", "chart")
	      .attr("width", 440)
	      .attr("height", 140)
	    .append("g")
	      .attr("transform", "translate(10,15)");
*/

/*	 chart.selectAll("line")
	      .data(x.ticks(10))
	    .enter().append("line")
	      .attr("x1", x)
	      .attr("x2", x)
	      .attr("y1", 0)
	      .attr("y2", 120)
	      .style("stroke", "#ccc");
*/
	 /*chart.selectAll(".rule")
	      .data(x.ticks(10))
	    .enter().append("text")
	      .attr("class", "rule")
	      .attr("x", x)
	      .attr("y", 0)
	      .attr("dy", -3)
	      .attr("text-anchor", "middle")
	      .text(String);*/


/*	 chart.append("line")
	      .attr("y1", 0)
	      .attr("y2", 120)
	      .style("stroke", "#000");
*/
}

function calculateCallTreeFrame(samples, start, max) {
    var s = samples[start];
    var current = { start: s.start, end: s.start + s.elapsed, elapsed: s.elapsed, name: s.name, children: [] };
    var nodes = [current];
    //console.log(start, samples.length);

    var i;
    for (i = start+1; i < samples.length ; i++) {
        var s = samples[i];
        var end = s.start + s.elapsed;

        // TODO: Correct range? Add depth to samples instead?
        if (end > max) {
            //++i;
            --i;
            break;
        }
        // Avoid floating point comparison error
        // If a child is taken for a sibling scope the total time will be totally wrong
        var epsilon = 0.00001;
        // TODO: Correct range? Add depth to samples instead?
        if (s.start >= current.start && (end - epsilon) <= current.end) {
            var ret = calculateCallTreeFrame(samples, i, end);
            i = ret.start;
            current.children = current.children.concat(ret.nodes);
        } else {
            current = { start: s.start, end: s.start + s.elapsed, elapsed: s.elapsed, name: s.name, children: [] };
            nodes.push(current);
        }
    }

    return { nodes: nodes, start: i };
}


function calculateCallTreeSelfTime(node) {
    var sum = 0;
    for (var i in node.children) {
        var c = node.children[i];
        sum += c.end - c.start;
        calculateCallTreeSelfTime(c);
    }
    node.self = (node.end - node.start) - sum;
}

function calculateCallTree(profile) {
    var frames = [];
    for (var i in profile) {
        var ret = calculateCallTreeFrame(profile[i].samples, 0, Number.MAX_VALUE);

        for (var i in ret.nodes) {
            var n = ret.nodes[i];
            calculateCallTreeSelfTime(n);
        }
        frames.push(ret.nodes);
    }
    return frames;
}

function calculateTopScopes(node, min, scopes) {
    if (node.self > min) {
        scopes[node.name] = node;
    }

    for (var i in node.children) {
        calculateTopScopes(node.children[i], min, scopes);
    }
}

function calculateScopes(profile) {
    var profileScopes = []

    for (var i in profile) {
        var scopes = {};
        var frame = profile[i];
        for (var j in frame.samples) {
            var sample = frame.samples[j];
            if (!(sample.scope_name in scopes)) {
                scopes[sample.scope_name] = { name: sample.scope_name, start: Number.MAX_VALUE, end: 0};
            }
            var scope = scopes[sample.scope_name];
            scope.start = Math.min(scope.start, sample.start);
            scope.end = Math.max(scope.end, sample.start + sample.elapsed);
            scope.elapsed = scope.end - scope.start;
        }
        for (var j in scopes) {
            var minOverlap = Number.MAX_VALUE;
            var maxOverlap = 0;
            var scope = scopes[j];
            var foundOverlap = false;
            for (var k in scopes) {
                if (j != k) {
                    var childScope = scopes[k];
                    if (childScope.start >= scope.start && childScope.end < scope.end) {
                        //console.log("overlap", j, k, scope.start, scope.end, childScope.start, childScope.end);
                        minOverlap = Math.min(minOverlap, childScope.start);
                        maxOverlap = Math.max(maxOverlap, childScope.end);
                        foundOverlap = true;
                    }
                }
                scope.overlapStart = minOverlap;
                scope.overlapEnd = maxOverlap;
                scope.selfElapsed = scope.elapsed - (maxOverlap - minOverlap);
            }

            if (!foundOverlap) {
                scope.selfElapsed = scope.elapsed;
            }
        }

        var sum = 0;
        for (var j in scopes) {
            sum += scopes[j].selfElapsed;
            scopes[j].foo = scopes[j].elapsed;
            scopes[j].elapsed = scopes[j].selfElapsed;
            //console.log(scopes[j].elapsed);
        }
        //console.log(sum);
        //console.log(scopes);

        //console.log(scopes);
        profileScopes.push( { scopes_data: scopes });
    }
    return profileScopes;
}

function testStack(profile) {
    $('#frameSumChart').empty();

    /*var testData = [
                    { samples: [ {start: 0, elapsed: 10, name: 'A' }, {start: 4, elapsed: 2, name: 'A1' }, {start: 10, elapsed: 1, name: 'B' }, {start: 11, elapsed: 8, name: 'C' }, ], },
                    { samples: [ {start: 0, elapsed: 10, name: 'A' }, {start: 4, elapsed: 2, name: 'A1' }, {start: 10, elapsed: 1, name: 'B' }, {start: 11, elapsed: 8, name: 'C' }, ], },
                    ];
                    */

    var testData = [
                    { samples: [ {start: 0.001, elapsed: 15.936, name: 'Engine.Frame' },
                                 {start: 0.023, elapsed: 0.398, name: 'Engine.Sim' },
                                 {start: 0.422, elapsed: 15.515, name: 'Graphics.Flip' }, ], },
                    ];

    //console.log(JSON.stringify(calculateCallTree(testData), undefined, 2));
   //var tmp = calculateCallTree(profile);
   var tmp = calculateCallTree(testData);
   var topScopes = {};
   //console.log(tmp);

   //profile = [];
   var newProfile = [];
   for (var i in tmp) {
       //console.log(tmp[i]);
       var ts = [];
       for (var j in tmp[i]) {
           calculateTopScopes(tmp[i][j], 0, ts);
       }
       //topScopes.push(ts);
       newProfile.push({scopes_data: ts});
       for (var k in ts) {
           //console.log("!!!!!!!", ts[k]);
           ts[k].elapsed = ts[k].self;
       }

       console.log(tmp[i], tmp[i].length);
       //console.log("ts", JSON.stringify(ts, undefined, 2));
       //console.log(ts);
       //if (i > 10)
         //  break; // TODO
   }
   if (false) {
       profile = newProfile;
   } else {
       profile = calculateScopes(profile);
   }
   //

   console.log(profile);

   //calculateTopScopes(tmp[0][0], 0.05, topScopes);
   //console.log(JSON.stringify(tmp, undefined, 2));
   //console.log(JSON.stringify(topScopes, undefined, 2));
   console.log("------------");
   console.log(topScopes);
   console.log("------------");
    //console.log(profile);
    //console.log(calculateScopes(profile));


    var data = [];

	var domainY = 0;

/*    var scopesToShow = {
            //"Graphics": true,
            "VSync" : true,
            "GameObject": true,
            "Script": true,
            "Physics": true,
            "Sprite": true,
            "Sound": true,
            "RenderScript": true,
            //"Particle": true,
            }
*/
    var scopesToShow = {
            "Engine.Frame": true,
            "VSync.Wait": true,
            }

   var scopeTotal = {}
    for (var i in profile) {
        for (var j in profile[i].scopes_data) {
            if (!(j in scopeTotal)) {
                scopeTotal[j] = 0;
            }
            scopeTotal[j] += profile[i].scopes_data[j].elapsed;
        }
    }

	var frameCount = profile.length;

	var threshold = frameCount * 0.5;

    for (var i in profile) {
    	var s = { "frame": parseInt(i) };
    	var sum = 0;

        var other = 0.0;
    	for (var j in profile[i].scopes_data) {
    	    var e = parseInt(profile[i].scopes_data[j].elapsed);
    	    if (true || j in scopesToShow) {
    	        if (scopeTotal[j] < threshold) {
    	            other += e;
    	        } else {
                    s[j] = e;
    	        }
                sum += e;
    	    }
    	}
        s["Other"] = other;
		domainY = Math.max(domainY, sum);
    	data.push(s);
    }

    domainY = Math.max(domainY, 20);

/*
	data = [{"frame": 0, "IE": 41.62, "Chrome": 22.36},
    {"frame": 10, "IE": 37.62, "Chrome": 25.36},
    {"frame": 20, "IE": 35.62, "Chrome": 35.36},
    ];

	domainY = 100.0;
*/
	var margin = {top: 20, right: 20, bottom: 30, left: 50},
	// TODO: 10 should be a constant
    width = Math.max(minChartWidth, frameCount * 10) - margin.left - margin.right,
    height = 500 - margin.top - margin.bottom;

//var parseDate = d3.time.format("%y-%b-%d").parse;
var formatPercent = d3.format(".0%");

var x = d3.scale.linear()
	.range([0, width]);
/*
var x = d3.time.scale()
	.range([0, width]);
*/

var y = d3.scale.linear()
	.domain([0,domainY * 1.1])
    .range([height, 0]);

var color = d3.scale.category20();

var xAxis = d3.svg.axis()
    .scale(x)
    .orient("bottom");

var yAxis = d3.svg.axis()
    .scale(y)
    .orient("left");
    //.tickFormat(formatPercent);

var area = d3.svg.area()
    .x(function(d) { return x(d.frame); })
    .y0(function(d) { return y(d.y0); })
    .y1(function(d) { return y(d.y0 + d.y); });

var stack = d3.layout.stack()
    .values(function(d) { return d.values; });

var svg = d3.select("#frameSumChart").append("svg")
    .attr("width", width + margin.left + margin.right)
    .attr("height", height + margin.top + margin.bottom)
    .append("g")
    .attr("transform", "translate(" + margin.left + "," + margin.top + ")");

/*d3.tsv("data.tsv", function(error, data) {
 */
color.domain(d3.keys(data[0]).filter(function(key) { return key !== "frame"; }));

/*
data.forEach(function(d) {
d.date = parseDate(d.date);
console.log(typeof d.date);
console.log(d.date);
});
*/

var browsers = stack(color.domain().map(function(name) {
return {
  name: name,
  values: data.map(function(d) {
    return {frame: d.frame, y: d[name]};
  })
};
}));

x.domain(d3.extent(data, function(d) { return d.frame; }));

var browser = svg.selectAll(".browser")
    .data(browsers)
  .enter().append("g")
    .attr("class", "browser");

browser.append("path")
    .attr("class", "area")
    .attr("d", function(d) { return area(d.values); })
    .attr("data-legend",function(d) { return d.name})
    .attr("data-legend-color",function(d) { return color(d.name)})
    .style("fill", function(d) { return color(d.name); });

/*
browser.append("text")
    .datum(function(d) { return {name: d.name, value: d.values[d.values.length - 1]}; })
    .attr("transform", function(d) { return "translate(" + x(d.value.frame) + "," + y(d.value.y0 + d.value.y / 2) + ")"; })
    .attr("x", -6)
    .attr("dy", ".35em")
    .text(function(d) { return d.name; });
*/

svg.append("g")
    .attr("class", "x axis")
    .attr("transform", "translate(0," + height + ")")
    .call(xAxis);

svg.append("g")
    .attr("class", "y axis")
    .call(yAxis);

/*
svg.append("g")
    .attr("class", "grid")
    .call(yAxis
    .tickSize(-100, 0, 0)
    .tickFormat("x"));*/

/*
svg.append("g")
    .attr("class", "grid")
    .line()
    .x(0)
    .y(10)
    .width(500);
*/

svg.append("line")
    .attr("x1", 0)
    .attr("x2", width)
    .attr("y1", y(1000/60.0))
    .attr("y2", y(1000/60.0))
    .style("stroke-dasharray", ("3, 3"))
    .style("stroke", "#aaa");

var legend = svg.append("g")
    .attr("class","legend")
    .attr("transform","translate(50,30)")
    .style("font-size","12px")
    .call(d3.legend)

//});

}

function startCapture() {
    //var url = 'http://172.16.10.49:8002/';
    var url = 'http://localhost:8002/';
    capture(url, function(profile) {
        testStack(profile);
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

function start() {

	/*capture('http://172.16.10.49:8002/', function(profile) {
		testStack(profile);
	});*/

	test2();


var t = 1297110663,
    v = 70,
    data = d3.range(150).map(next);


function next() {
	v = ~~Math.max(10, Math.min(90, v + 10 * (Math.random() - .5)));
	//console.log(v);
  return {
    time: ++t,
    value: v
  };
}

//console.log(data);



var w = 20,
    h = 80;

var x = d3.scale.linear()
    .domain([0, 1])
    .range([0, w]);

var y = d3.scale.linear()
    .domain([0, 100])
    .rangeRound([0, h]);

function click(d) {
	console.log(d);
}

var chart2 = d3.select("#chart").append("svg")
    .attr("class", "chart")
    .attr("width", w * data.length - 1)
    .attr("height", h);


chart2.append("line")
    .attr("x1", 0)
    .attr("x2", w * data.length)
    .attr("y1", h - .5)
    .attr("y2", h - .5)
    .style("stroke", "#000");


redraw2();

var tooltip = d3.select("#tooltip")
	.text("a simple tooltip");



/*var tooltip = d3.select("body")
	.append("div")
	.style("position", "absolute")
	.style("z-index", "10")
	.style("visibility", "hidden")
	.text("a simple tooltip");
*/
function redraw2() {

  var rect = chart2.selectAll("rect")
      .data(data, function(d) { return d.time; });

  rect.enter().insert("rect", "line")
      .attr("x", function(d, i) { return x(i) - .5; })
      .attr("y", function(d) { return h - y(d.value) - .5; })
      .attr("width", w)
      .attr("height", function(d) { return y(d.value); });

  rect.append("svg:title").text(function(d) { return "foo"; });

  rect.transition()
      .duration(0)
      .attr("x", function(d, i) { /* console.log(x(i), d,i); */ return x(i) - 0.5; });

  chart2.selectAll("rect").on("click", click);

  chart2.selectAll("rect")
	.on("mouseover", function(){ return tooltip.style("visibility", "visible");} )
	.on("mousemove", function(d){ tooltip.text(d.value + " ms"); return tooltip.style("top", (event.pageY-10)+"px").style("left",(event.pageX+10)+"px");} )
	.on("mouseout", function(){ return tooltip.style("visibility", "hidden");} );

  rect.exit()
      .remove();

}

/*
setInterval(function() {
	data.shift();
   data.push(next());
   redraw2();
	}, 2500);
*/

}

