
var running = false;

/*
  TODO:
  * The "Create time" might exclude relevant scopes, e.g. DDF. We should have precise calculation that excludes sub-resources but includes all other scopes.


 */

var extToName = {
    "texturec": "Texture",
    "fontc": "Font",
    "gui_scriptc": "Gui Script",
    "wavc": "Wav",
    "oggc": "Ogg",
    "scriptc": "Script",
    "collectionc": "Collection",
    "luac": "Lua",
    "guic": "Gui",
    "goc": "Game Object",
    "materialc": "Material",
    "fpc": "Fragment Shader",
    "particlefxc": "Particle",
    "vpc": "Vertex Shader",
    "soundc": "Sound",
    "texturesetc": "Texture Set",
    "factoryc": "Factory",
    "render_scriptc": "Render Script",
    "input_bindingc": "Input Binding",
    "renderc": "Render",
    "collectionproxyc": "Collection Proxy",
    "gamepadsc": "Game Pad"
};

function showTable(ext, samples) {
    d3.select("tbody").selectAll("tr").remove();

    $('#resources').show();

    var resources = {};
    for (var i = 0; i < samples.length; ++i) {
        var s = samples[i];
        if (s.ext == ext) {
            var x = resources[s.resource];
            if (x == undefined) {
                x = {resource: s.resource, load: 0, create: 0};
            }

            if (s.name == "Resource.Load") {
                x.load += s.self;
            } else if (s.name == "Resource.Create") {
                x["create"] += s.self;
            }

            resources[s.resource] = x;
//            lst.push(s);
        }
    }

    var lst = [];
    for (var r in resources) {
        lst.push(resources[r]);
    }

    lst.sort(function(a,b) { return (b.load + b.create) - (a.load + b.create); });

/*    var th = d3.select("thead").selectAll("th")
        .data(jsonToArray(data[0]))
        .enter().append("th")
        .attr("onclick", function (d, i) { return "transform('" + d[0] + "');";})
        .text(function(d) { return d[0]; })
*/

    var tr = d3.select("tbody").selectAll("tr")
        .data(lst)
        .enter().append("tr");

    var td = tr.selectAll("td")
        .data(function(d) {
            return [ d.resource, d.load.toFixed(1), d.create.toFixed(1), (d.load + d.create).toFixed(1) ];
        })
        .enter().append("td")
        //.attr("onclick", function (d, i) { return "transform('" + d[0] + "');";})
        .text(function(d) {
            return d;
            //return "Hej";
        });

}

function drawPie(data, samples) {

    var w = 500;                        //width
    var h = 500;                            //height
    var r = radius = Math.min(w, h) / 2 - 10;
    var centerDX = (w - r) / 2;
    var centerDY = (w - r) / 2;
    var inner = r * 0.4;
    var color = d3.scale.category20();     //builtin range of colors

    /*    data = [{"label":"one", "value":20},
          {"label":"two", "value":50},
          {"label":"three", "value":30}];*/

    d3.select("#chart").remove();

    var getName = function (ext) {
        var n = extToName[ext];
        if (n) {
            return n;
        } else {
            return ext;
        }
    };

    var vis = d3.select("#chartContainer")
        .append("svg:svg")              //create the SVG element inside the <body>
        .attr("id", "chart")
        .data([data])                   //associate our data with the document
        .attr("width", w + 200)           //set the width and height of our visualization (these will be attributes of the <svg> tag
        .attr("height", h)
        .append("svg:g")                //make a group to hold our pie chart
        .attr("transform", "translate(" + (w/2) + "," + (h/2) + ")")    //move the center of the pie chart from 0, 0 to radius, radius

    var centerText1 = vis.append("text")
        .style("text-anchor", "middle")
        .attr("dy", "-0.5em")
        .style("font-size", "2em")
        .classed("chart-text", true)
        .text("");

    var centerText2 = vis.append("text")
        .style("text-anchor", "middle")
        .classed("chart-text", true)
        .attr("dy", "0.7em")
        .text("");

    var centerText3 = vis.append("text")
        .style("text-anchor", "middle")
        .classed("chart-text", true)
        .attr("dy", "2.5em")
        .text("");

    var arc = d3.svg.arc()              //this will create <path> elements for us using arc data
        .outerRadius(r)
        .innerRadius(inner);

    var arcOver = d3.svg.arc()
        .outerRadius(r + 10)
        .innerRadius(inner + 10);

    var pie = d3.layout.pie()           //this will create arc data for us given a list of values
        .value(function(d, i) {
            return d.load + d.create;
        });    //we must tell it out to access the value of each element in our data array

    var arcs = vis.selectAll("g.slice")     //this selects all <g> elements with class slice (there aren't any yet)
        .data(pie)                          //associate the generated pie data (an array of arcs, each having startAngle, endAngle and value properties)
        .enter()                            //this will create <g> elements for every "extra" data element that should be associated with a selection. The result is creating a <g> for every object in the data array
        .append("svg:g")                //create a group to hold each slice (we will have a <path> and a <text> element associated with each slice)
        .attr("class", "slice")    //allow us to style things in the slices (like text)
        .on("mousedown", function(d) {
            showTable(d.data.ext, samples);
        })
        .on("mouseover", function(d) {
            d3.select(this).select("path").transition()
                .duration(200)
                .attr("d", arcOver);

            var angle = d.endAngle - d.startAngle;
            var per = 100 * angle / 6.28;
            centerText1.text("" + per.toFixed(1) + " %");
            centerText2.text(getName(d.data.ext));
            centerText3.text((d.data.load + d.data.create).toFixed(1) + " ms");

            var tip = d3.select("#tooltip")
            tip.transition()
                .duration(400)
                .style("opacity", .9);
            tip.html("<span style=\"font-size: 1.2em;\">" + getName(d.data.ext) + "</span><br>" +  "<b>Load:</b> "  + d.data.load.toFixed(2) + " ms<br/><b>Create:</b> "  + d.data.create.toFixed(2) + " ms<br/>")
                .style("left", (d3.event.pageX) + "px")
                .style("top", (d3.event.pageY - 28) + "px");

        })
        .on("mouseout", function() {
            d3.select(this).select("path").transition()
                .duration(200)
                .attr("d", arc);
            centerText1.text("");
            centerText2.text("");
            centerText3.text("");

            var tip = d3.select("#tooltip")
            tip.transition()
                .duration(500)
                .style("opacity", 0);
        });

    arcs.append("svg:path")
        .attr("fill", function(d, i) {
            return color(i);
        } ) //set the color for each slice to be chosen from the color function defined above
        .attr("d", arc);                                    //this creates the actual SVG path using the associated data (pie) with the arc drawing function

    arcs.append("svg:text")                                     //add a label to each slice
        .attr("transform", function(d) {                    //set the label's origin to the center of the arc
            //we have to make sure to set these before calling arc.centroid
            d.innerRadius = 0;
            d.outerRadius = r;
            return "translate(" + arc.centroid(d) + ")";        //this gives us a pair of coordinates like [50, 50]
        })
        .attr("text-anchor", "middle")                          //center the text on it's origin
        .text(function(d, i) { return getName(data[i].ext); })        //get the label from our original data array
        .classed("chart-text", true)
        .style("opacity", function(d) {
            var angle = d.endAngle - d.startAngle;

            if (angle < 6.28 * 0.08)
                return 0;
            else
                return 1;
        })
        .on("mouseover", function() { d3.select(d3.event.target).classed("highlight", true); })
        .on("mouseout", function() { d3.select(d3.event.target).classed("highlight", false); });

    /*    var legend = d3.select("body")
          .append("svg:svg")
          .append("g")
          .attr("class", "legend");
    */

    var legend = vis.append("g")
        .attr("class", "legend");


    //	var legend = vis.append("g")
    //	  .attr("class", "legend");
    //.attr("x", w - 65)
    //.attr("y", 50)
	///.attr("height", 400)
	//.attr("width", 400);
    //.attr('transform', 'translate(-20,50)')


    legend.selectAll('rect')
        .data(data)
        .enter()
        .append("rect")
	    .attr("x", r + 20)
        .attr("y", function(d, i){ return i *  20 - r;})
	    .attr("width", 20)
	    .attr("height", 20)
	    .style("fill", function(d, i) {
            //var color = color_hash[dataset.indexOf(d)][1];
            return color(i);
        });


    legend.selectAll('text')
        .data(data)
        .enter()
        .append("text")
	    .attr("x", r + 50)
        .attr("y", function(d, i){ return i *  20  - r;})
        .attr("dy", "1.1em")
	    .text(function(d) {
            return getName(d.ext);
        });
}

function startCapture() {
    //    var url = 'http://localhost:8002/';
    //var url = 'http://192.168.1.2:8002/';
    //var url = 'http://192.168.1.2:8002/';
    //var url = 'http://172.16.10.191:8002/';
    //var url = 'http://172.16.10.207:8002/';
    //var url = "http://localhost:8000/";

    var url = 'http://172.16.10.106:8002/';
//    var url = 'http://172.16.10.191:8002/';

    var progressFunc = function(n) {
        $('#captureCount').text(n);
    }

    profiler.startCapture(url, progressFunc, function(profile, strings) {
        var callTree = profile.map(profiler.callTree);
        var topFrames = callTree.map(profiler.flatten);
        foo = topFrames
        var samples = [];

        total = {}
        for (var i in topFrames) {
            var frame = topFrames[i];
            for (var j in frame) {
                var sample = frame[j];
                if (sample.name == "Resource.Load") {
                    var resource = strings[sample.arg];
                    var tmp = resource.split(".");
                    var ext = tmp[tmp.length-1];
                    var x = total[ext]
                    if (x == undefined) {
                        x = {load: 0, create: 0};
                    }
                    x.load += sample.self;
                    total[ext] = x;

                    sample.ext = ext;
                    sample.resource = resource;
                    samples.push(sample);
                }
                else if (sample.name == "Resource.Create") {
                    var resource = strings[sample.arg];
                    var tmp = resource.split(".");
                    var ext = tmp[tmp.length-1];
                    var x = total[ext]
                    if (x == undefined) {
                        x = {load: 0, create: 0};
                    }
                    x.create += sample.self;
                    total[ext] = x;

                    sample.ext = ext;
                    sample.resource = resource;
                    samples.push(sample);
                }
            }
        }

        var data = [];
        for (var ext in total) {
            data.push({ext: ext, load: total[ext].load, create: total[ext].create});
        }
        data.sort(function(a,b){return (b.load + b.create)  - (a.load + a.create)});
        console.log( data );


        if (data.length > 0)
            drawPie(data, samples);
        else
            $('#noData').show();

    });
}

function onCapture() {

    var data = [{"ext":"one", "load":20, "create": 5},
                {"ext":"two", "load":50, "create": 15},
                {"ext":"three", "load":30, "create": 8}];

    d3.select("tbody").selectAll("tr").remove();
    $('#noData').hide();
    $('#resources').hide();
    $('#captureCount').text('');

    if (running) {
        $('#captureButton').text("Capture");
        $('#capturing').hide();
        running = false;
        profiler.stopCapture();
    } else {
        //drawPie(data, [{ext: "one", self: 10, resource: "/tmp/x.png"}]);
        $('#captureButton').text("Stop");
        $("#capturing").show();
        running = true;
        startCapture();
    }
}
