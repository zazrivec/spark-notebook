function convertAllSVGsToPngs() {
  var svgs = $("svg").each(function(i, svg) {
    var s = $(svg);
    var img = $("<img class='removeImgs'>");
    svg.toDataURL("image/png", {
        callback: function(data) {
            img.get(0).setAttribute("src", data)
        }
    });
    s.hide();
    s.after(img);
  });
};

function restoreSVGs() {
  $("img.removeImgs").remove();
  $("svg").show();
};