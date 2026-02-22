/*
Node.js translation of the provided Main.java
- Exposes GET / serving an HTML page with a textarea and a button that POSTS the compose YAML to POST /up
- POST /up accepts raw YAML body, parses it, adds defaults (container_name, restart, traefik labels, homelab networks), ensures required docker networks exist, then runs `docker compose -f - up -d --wait` streaming the generated YAML to docker's stdin
- Uses `js-yaml` for YAML parsing/dumping

Note: This file prefers `var` per project style.
*/

var http = require('http');
var url = require('url');
var yaml = require('js-yaml');
var { spawn } = require('child_process');

var PORT = process.env.PORT || 8080;

// Configuration equivalent to Java defaults
var INTERNAL_DOMAIN = process.env.INTERNAL_DOMAIN || 'localhost';
var EXTERNAL_DOMAIN = process.env.EXTERNAL_DOMAIN || null; // set if you have external domain
var LOAD_BALANCER_NETWORK = process.env.LOAD_BALANCER_NETWORK || null; // optional

function execute(command, stdinString, outputStream) {
  // command: array of args, first is executable
  return new Promise(function (resolve, reject) {
    var proc = spawn(command[0], command.slice(1));
    proc.stdout.on('data', function (d) { outputStream(d); });
    proc.stderr.on('data', function (d) { outputStream(d); });

    proc.on('error', function (err) {
      outputStream(err)
      reject(err);
    });

    proc.on('close', function (code) {
      if (code !== 0) {
        var err = new Error('Command failed with code ' + code);

      }
      resolve({ code: code, stdout: Buffer.concat(stdout).toString('utf8'), stderr: Buffer.concat(stderr).toString('utf8') });
    });

    if (stdinString) {
      proc.stdin.write(stdinString);
    }
    proc.stdin.end();
  });
}

async function getExistingNetworks() {
  try {
    var res = await execute(['docker', 'network', 'ls', '--format', '{{.name}}']);
    if (res.code !== 0) return new Set();
    return new Set(res.stdout.split(/\s+/).filter(Boolean));
  } catch (e) {
    return new Set();
  }
}

function getNetworks(root) {
  var networks = root.networks;
  if (networks == null) {
    networks = {};
    root.networks = networks;
  }
  if (typeof networks !== 'object' || Array.isArray(networks)) {
    throw new Error("Unsupported .networks declaration");
  }
  return networks;
}

function getServices(root) {
  var services = root.services;
  if (services == null) throw new Error("Compose YAML must contain 'services' mapping");
  if (typeof services !== 'object' || Array.isArray(services)) throw new Error("Invalid service declaration. Must be a dictionary.");
  // ensure each service is an object
  Object.entries(services).forEach(function (e) {
    if (typeof e[1] !== 'object' || Array.isArray(e[1])) throw new Error('Invalid services.' + e[0] + ' declaration. Must be a dictionary.');
  });
  return services;
}

function getNetworkRefs(serviceRef, service) {
  if (serviceRef == null) throw new Error('serviceRef cannot be null');
  if (service == null) throw new Error('service cannot be null');
  var networkRefs = service.networks;
  if (networkRefs == null) return [];
  if (!Array.isArray(networkRefs) || !networkRefs.every(function (x) { return typeof x === 'string'; })) {
    throw new Error('Unsupported services.' + serviceRef + '.networks declaration. Only list of strings currently supported.');
  }
  return networkRefs;
}

function getLabels(serviceRef, service) {
  var labelsObj = service.labels;
  var labels = {};
  if (labelsObj == null) return labels;
  if (typeof labelsObj === 'object' && !Array.isArray(labelsObj)) {
    Object.assign(labels, labelsObj);
  } else if (Array.isArray(labelsObj)) {
    labelsObj.forEach(l => {
      if (typeof l === 'string') {
        var idx = l.indexOf('=');
        if (idx > 0) {
          labels[l.substring(0, idx)] = l.substring(idx + 1);
        } else {
          labels[l] = '';
        }
      }
    });
  }
  return labels;
}

async function composeUp(root) {
  // root is an object parsed from YAML
  var existingNetworks = await getExistingNetworks();
  var networks = getNetworks(root);
  var networksToCreate = new Set();

  var services = getServices(root);

  Object.entries(services).forEach(function (entry) {
    var serviceRef = entry[0];
    var service = entry[1];
    if (service.container_name == null) service.container_name = serviceRef;
    if (service.restart == null) service.restart = 'unless-stopped';

    var refs = getNetworkRefs(serviceRef, service);
    refs.forEach(function (networkRef) {
      var network = networks[networkRef] || {};
      network.name = network.name || networkRef;
      network.driver = 'external';
      networks[networkRef] = network;
      networksToCreate.add(networkRef);
    });

    var labels = getLabels(serviceRef, service);
    if (labels.hasOwnProperty('http.port')) {
      delete labels['http.port'];
      labels['traefik.enable'] = labels['traefik.enable'] || 'true';
      var rule;
      if (EXTERNAL_DOMAIN && EXTERNAL_DOMAIN.trim() !== '') {
        rule = 'Host(`' + serviceRef + '.' + EXTERNAL_DOMAIN + '`) || Host(`' + serviceRef + '.' + INTERNAL_DOMAIN + '`)';
      } else {
        rule = 'Host(`' + serviceRef + '.' + INTERNAL_DOMAIN + '`)';
      }
      labels['traefik.http.routers.' + serviceRef + '.rule'] = labels['traefik.http.routers.' + serviceRef + '.rule'] || rule;

      if (LOAD_BALANCER_NETWORK && LOAD_BALANCER_NETWORK.trim() !== '') {
        var lb = networks[LOAD_BALANCER_NETWORK] || {};
        lb.name = lb.name || LOAD_BALANCER_NETWORK;
        lb.driver = 'external';
        networks[LOAD_BALANCER_NETWORK] = lb;
        networksToCreate.add(LOAD_BALANCER_NETWORK);
      }

      // write labels back in the same shape: prefer object mapping
      service.labels = Object.assign({}, labels);
    }
  });

  // create missing networks
  var createdNetworks = [];
  try {
    for (var net of networksToCreate) {
      if (!existingNetworks.has(net)) {
        var res = await execute(['docker', 'network', 'create', '--driver', 'bridge', net]);
        if (res.code !== 0) {
          throw new Error('Failed to create network ' + net + ': ' + res.stderr);
        }
        createdNetworks.push(net);
      }
    }
  } catch (e) {
    // cleanup created networks
    for (var cn of createdNetworks) {
      await execute(['docker', 'network', 'rm', '-f', cn]).catch(function () {});
    }
    throw e;
  }

  // run docker compose with YAML on stdin
  var outYaml = yaml.dump(root, { flowLevel: -1 });
  try {
    var composeRes = await execute(['docker', 'compose', '-f', '-', 'up', '-d', '--wait'], outYaml);
    if (composeRes.code !== 0) {
      // on failure, cleanup created networks
      for (var cn2 of createdNetworks) {
        await execute(['docker', 'network', 'rm', '-f', cn2]).catch(function () {});
      }
      throw new Error('docker compose failed: ' + composeRes.stderr || composeRes.stdout);
    }
    return composeRes.stdout || composeRes.stderr;
  } catch (e) {
    for (var cn3 of createdNetworks) {
      await execute(['docker', 'network', 'rm', '-f', cn3]).catch(function () {});
    }
    throw e;
  }
}

function collectRequestBody(req) {
  return new Promise(function (resolve, reject) {
    var parts = [];
    req.on('data', function (d) { parts.push(d); });
    req.on('end', function () { resolve(Buffer.concat(parts).toString('utf8')); });
    req.on('error', reject);
  });
}


var endpoints = {
    '/stacks': {
        'POST': (res, yaml) => {

        },
    }
}

var server = http.createServer(async function (req, res) {
  var parsed = url.parse(req.url, true);
  try {
    if (!endpoints[parsed.pathname]) {
         res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
         res.end('Not Found');
         return;
      }
    if (!endpoints[parsed.pathname][req.method.toUppercase()]) {
       res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' });
       res.end('Method Not Allowed');
    }
    if (req.method.toUppercase() === 'POST') {
        var root = yaml.load(body);
        if (typeof root !== 'object') {
            res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
            return;
        }
        endpoints[parsed.pathname]['POST'](res, yaml)
    }

      try {
        var result = await composeUp(root);
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(String(result));
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('Error: ' + (e && e.message ? e.message : String(e)));
      }
      return;
    }

    res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Method Not Allowed');
  } catch (e) {
    res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Server error: ' + (e && e.message ? e.message : String(e)));
  }
});

server.listen(PORT, function () {
  console.log('Server running at http://localhost:' + PORT + '/');
});
