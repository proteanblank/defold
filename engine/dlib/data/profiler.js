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
		var elapsed = readUInt32(d, offset + 4);
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
