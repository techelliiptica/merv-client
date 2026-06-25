function __mervKpiSuiteDurChartConfig(lab,dur,fmtKpiDur){
    return {
        type:'bar',
        data:{
            labels:lab,
            datasets:[{
                label:'Suite duration (s)',
                data:dur,
                backgroundColor:'rgba(23,162,184,0.65)',
                borderColor:'#138496',
                borderWidth:1
            }]
        },
        options:{
            responsive:true,
            maintainAspectRatio:false,
            plugins:{
                title:{display:true,text:'Suite execution time by run',font:{size:13,weight:'600'}},
                tooltip:{
                    callbacks:{
                        label:function(ctx){
                            var y=ctx.parsed.y!=null?ctx.parsed.y:ctx.raw;
                            return fmtKpiDur(Number(y));
                        }
                    }
                }
            },
            scales:{
                x:{ticks:{maxRotation:45,minRotation:0}},
                y:{beginAtZero:true,ticks:{callback:function(v){return v+' s';}}}
            }
        }
    };
}
function __mervKpiPerfSlowChartConfig(labS,datS,fmtKpiDur){
    return {
        type:'bar',
        data:{
            labels:labS,
            datasets:[{
                label:'Duration (s)',
                data:datS,
                backgroundColor:'rgba(220,53,69,0.72)',
                borderColor:'#b02a37',
                borderWidth:1
            }]
        },
        options:{
            indexAxis:'y',
            responsive:true,
            maintainAspectRatio:false,
            plugins:{
                title:{display:true,text:'Slowest test cases (max per name)',font:{size:13,weight:'600'}},
                tooltip:{
                    callbacks:{
                        label:function(ctx){
                            var x=ctx.parsed.x!=null?ctx.parsed.x:ctx.raw;
                            return fmtKpiDur(Number(x));
                        }
                    }
                }
            },
            scales:{
                x:{
                    beginAtZero:true,
                    ticks:{callback:function(v){return v+' s';}}
                },
                y:{ticks:{autoSkip:false}}
            }
        }
    };
}
