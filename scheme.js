(function(){
var svg=document.getElementById('schemeSvg');
var NS='http://www.w3.org/2000/svg';
function el(n,a){var e=document.createElementNS(NS,n);for(var k in a){e.setAttribute(k,a[k]);}return e;}
var state={stations:[],lines:[],byId:{}};
function load(){return fetch('data/stations_v3.json').then(function(r){return r.json();}).then(function(v){state.stations=v.stations;v.stations.forEach(function(s){state.byId[s.id]=s;});return fetch('data/lines.json');}).then(function(r){return r.json();}).then(function(n){state.lines=n.lines;render();});}
function render(){if(!svg)return;svg.innerHTML='';var g=el('g',{});svg.appendChild(g);state.lines.forEach(function(line){var pts=line.stations.map(function(s){var st=null;state.stations.forEach(function(x){if(x.name===s.name)st=x;});return st?[st.sx,st.sy]:null;}).filter(Boolean);if(pts.length<2)return;var d='M'+pts.map(function(p){return p[0]+' '+p[1];}).join(' L');g.appendChild(el('path',{d:d,fill:'none',stroke:line.color,'stroke-width':5,'stroke-linejoin':'round','stroke-linecap':'round'}));});state.stations.forEach(function(s){var c=el('circle',{cx:s.sx,cy:s.sy,r:s.lines.length>1?7:4,fill:'#0b0e14',stroke:'#fff','stroke-width':2});c.addEventListener('click',function(){if(window.showStationCard)window.showStationCard(s);});g.appendChild(c);var t=el('text',{x:s.sx+8,y:s.sy+4,fill:'#e5e7eb','font-size':11});t.textContent=s.name;g.appendChild(t);});}
function fitVisible(){if(!svg)return;var xs=state.stations.map(function(s){return s.sx;});var ys=state.stations.map(function(s){return s.sy;});if(!xs.length)return;svg.setAttribute('viewBox',(Math.min.apply(null,xs)-40)+' '+(Math.min.apply(null,ys)-40)+' '+((Math.max.apply(null,xs)-Math.min.apply(null,xs))+80)+' '+((Math.max.apply(null,ys)-Math.min.apply(null,ys))+80));}
window.Scheme={load:load,render:render,fitVisible:fitVisible,state:state};
document.addEventListener('DOMContentLoaded',load);
})();
